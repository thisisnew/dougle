package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DatadogLogsClient implements AutoCloseable {
    public enum Site {
        US1("api.datadoghq.com"), US3("api.us3.datadoghq.com"), US5("api.us5.datadoghq.com"),
        EU1("api.datadoghq.eu"), AP1("api.ap1.datadoghq.com"), AP2("api.ap2.datadoghq.com"), UK1("api.uk1.datadoghq.com");
        private final String host;
        Site(String host) { this.host = host; }
        public URI endpoint() { return URI.create("https://" + host + "/api/v2/logs/events/search"); }
    }

    public record Config(Site site, String apiKey, String applicationKey) {
        public Config {
            Objects.requireNonNull(site);
            credential(apiKey);
            credential(applicationKey);
        }
        @Override public String toString() { return "Datadog credentials [redacted]"; }
    }

    public record Query(String environment, String service, Instant from, Instant to, int limit) {
        public Query {
            tag(environment);
            tag(service);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            if (!from.isBefore(to) || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid log query");
        }
    }

    public record Policy(Map<String, Set<String>> servicesByEnvironment, Set<String> indexes, Instant earliest,
                         Instant latest, Duration maxWindow, int maxRows, int maxBytes, Duration timeout) {
        public Policy {
            var copy = new TreeMap<String, Set<String>>();
            servicesByEnvironment.forEach((environment, services) -> {
                tag(environment);
                if (services.isEmpty() || services.size() > 32) throw new IllegalArgumentException("Invalid services");
                services.forEach(DatadogLogsClient::tag);
                copy.put(environment, Set.copyOf(services));
            });
            servicesByEnvironment = Map.copyOf(copy);
            indexes = Set.copyOf(indexes);
            indexes.forEach(DatadogLogsClient::tag);
            Objects.requireNonNull(earliest);
            Objects.requireNonNull(latest);
            Objects.requireNonNull(maxWindow);
            Objects.requireNonNull(timeout);
            if (copy.isEmpty() || copy.size() > 16 || indexes.isEmpty() || indexes.size() > 8
                    || !earliest.isBefore(latest) || Duration.between(earliest, latest).compareTo(Duration.ofDays(7)) > 0
                    || maxWindow.isNegative() || maxWindow.isZero() || maxWindow.compareTo(Duration.ofHours(1)) > 0
                    || maxRows < 1 || maxRows > 100 || maxBytes < 512 || maxBytes > 65536
                    || timeout.compareTo(Duration.ofMillis(50)) < 0 || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
                throw new IllegalArgumentException("Invalid log policy");
            }
        }
    }

    public record Result(String output, int rows, boolean truncated) {}
    private final JsonMapper json = new JsonMapper();
    private final URI fixtureEndpoint;
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient http = HttpClients.custom()
            .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries().disableContentCompression()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(15)).build()).build();
    private final java.util.concurrent.ScheduledExecutorService cancellations = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("datadog-read-deadline").factory());

    public DatadogLogsClient() { fixtureEndpoint = null; }

    DatadogLogsClient(URI fixtureEndpoint) {
        if (!"http".equals(fixtureEndpoint.getScheme()) || !"127.0.0.1".equals(fixtureEndpoint.getHost())
                || fixtureEndpoint.getRawUserInfo() != null || fixtureEndpoint.getRawQuery() != null
                || fixtureEndpoint.getRawFragment() != null || !"/api/v2/logs/events/search".equals(fixtureEndpoint.getPath())) {
            throw new IllegalArgumentException("Synthetic loopback endpoint required");
        }
        this.fixtureEndpoint = fixtureEndpoint;
    }

    public Result read(Config config, Policy policy, Query query, Runnable checkpoint) {
        validate(policy, query);
        checkpoint.run();
        var request = new HttpPost(fixtureEndpoint == null ? config.site().endpoint() : fixtureEndpoint);
        request.setHeader("DD-API-KEY", config.apiKey());
        request.setHeader("DD-APPLICATION-KEY", config.applicationKey());
        request.setHeader("Accept", "application/json");
        String filter = "env:\"" + query.environment() + "\" AND service:\"" + query.service() + "\"";
        request.setEntity(new StringEntity(json.writeValueAsString(Map.of("filter", Map.of("query", filter,
                "from", query.from().toString(), "to", query.to().toString(), "indexes", policy.indexes().stream().sorted().toList()),
                "sort", "timestamp", "page", Map.of("limit", query.limit()))), ContentType.APPLICATION_JSON));
        long started = System.nanoTime();
        var stopped = new AtomicReference<RuntimeException>();
        var deadline = cancellations.scheduleAtFixedRate(() -> {
            try {
                checkpoint.run();
                if (System.nanoTime() - started >= policy.timeout().toNanos()) throw new LibraryException(504, "Datadog read timed out");
            } catch (RuntimeException stop) {
                stopped.compareAndSet(null, stop);
                request.cancel();
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        try {
            var result = http.execute(request, response -> {
                if (response.getCode() != 200) {
                    int code = response.getCode();
                    throw new LibraryException(code == 401 || code == 403 || code == 429 ? code : 502, "Datadog log access failed");
                }
                if (response.getEntity() == null) throw new LibraryException(502, "Invalid Datadog response");
                try (var input = response.getEntity().getContent()) {
                    byte[] body = input.readNBytes(1_048_577);
                    if (body.length > 1_048_576) throw new LibraryException(502, "Datadog response exceeds limit");
                    checkpoint.run();
                    return render(config, policy, query, body, checkpoint);
                }
            });
            if (stopped.get() != null) throw stopped.get();
            checkpoint.run();
            if (System.nanoTime() - started >= policy.timeout().toNanos()) throw new LibraryException(504, "Datadog read timed out");
            return result;
        } catch (Exception failure) {
            if (stopped.get() != null) throw stopped.get();
            checkpoint.run();
            if (failure instanceof CancellationException cancelled) throw cancelled;
            if (failure instanceof LibraryException problem) throw problem;
            throw new LibraryException(502, "Datadog log access failed");
        } finally {
            deadline.cancel(false);
            request.cancel();
        }
    }

    public static void validate(Policy policy, Query query) {
        if (!policy.servicesByEnvironment().getOrDefault(query.environment(), Set.of()).contains(query.service())
                || query.from().isBefore(policy.earliest()) || query.to().isAfter(policy.latest())
                || Duration.between(query.from(), query.to()).compareTo(policy.maxWindow()) > 0 || query.limit() > policy.maxRows()) {
            throw new IllegalArgumentException("Log query outside approved scope");
        }
    }

    private Result render(Config config, Policy policy, Query query, byte[] body, Runnable checkpoint) {
        var root = json.readTree(body);
        var data = root.path("data");
        if (!data.isArray()) throw new LibraryException(502, "Invalid Datadog response");
        if ("timeout".equals(root.path("meta").path("status").asText())) throw new LibraryException(504, "Datadog search incomplete");
        boolean truncated = data.size() > query.limit() || !root.path("meta").path("page").path("after").asText("").isEmpty();
        var rows = new ArrayList<Map<String, String>>();
        List<String> secrets = List.of(config.apiKey(), config.applicationKey());
        for (var event : data) {
            checkpoint.run();
            if (rows.size() >= query.limit()) break;
            var attributes = event.path("attributes");
            boolean environmentMatches = false;
            for (var value : attributes.path("tags")) {
                String tag = value.asText();
                if (tag.startsWith("env:") && !("env:" + query.environment()).equals(tag)) {
                    throw new LibraryException(502, "Datadog returned evidence outside approved scope");
                }
                if (("env:" + query.environment()).equals(tag)) environmentMatches = true;
            }
            Instant timestamp = Instant.parse(attributes.path("timestamp").asText());
            if (!environmentMatches || !query.service().equals(attributes.path("service").asText())
                    || timestamp.isBefore(query.from()) || timestamp.isAfter(query.to())) {
                throw new LibraryException(502, "Datadog returned evidence outside approved scope");
            }
            String message = DatadogLogRedactor.redact(attributes.path("message").asText(""), secrets);
            if (message.length() > 2000) { message = clip(message, 2000); truncated = true; }
            String id = DatadogLogRedactor.redact(event.path("id").asText(""), secrets);
            if (id.length() > 128) { id = clip(id, 128); truncated = true; }
            rows.add(Map.of("id", id, "timestamp", timestamp.toString(), "environment", query.environment(),
                    "service", query.service(), "message", message));
            if (encoded(rows, false).getBytes(StandardCharsets.UTF_8).length > policy.maxBytes()) {
                rows.removeLast();
                truncated = true;
                break;
            }
        }
        if (rows.isEmpty() && !truncated) return new Result("", 0, false);
        return new Result(encoded(rows, truncated), rows.size(), truncated);
    }

    private String encoded(List<Map<String, String>> rows, boolean truncated) {
        return json.writeValueAsString(Map.of("logs", rows, "truncated", truncated, "redacted", true));
    }

    private static String clip(String value, int limit) {
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end);
    }

    private static void tag(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid log scope identifier");
    }

    private static void credential(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,256}")) throw new IllegalArgumentException("Invalid Datadog credentials");
    }

    @Override @PreDestroy
    public void close() throws java.io.IOException {
        cancellations.shutdownNow();
        http.close();
    }
}
