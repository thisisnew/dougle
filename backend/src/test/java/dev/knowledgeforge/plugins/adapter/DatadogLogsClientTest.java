package dev.knowledgeforge.plugins.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.knowledgeforge.library.application.LibraryException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(10)
class DatadogLogsClientTest {
    private static final Instant FROM = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant TO = FROM.plusSeconds(1800);
    private static final DatadogLogsClient.Config CONFIG = new DatadogLogsClient.Config(DatadogLogsClient.Site.US1,
            "synthetic_api_key_0000000000000000", "synthetic_app_key_0000000000000000");
    private final JsonMapper json = new JsonMapper();
    private HttpServer server;
    private DatadogLogsClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> appKey = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean blocked = new AtomicBoolean();
    private java.util.concurrent.ExecutorService serverWorkers;

    @BeforeEach
    void prepare() throws Exception {
        response.set(body("test", "orders", "fixture message"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverWorkers);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().toString());
            apiKey.set(exchange.getRequestHeaders().getFirst("DD-API-KEY"));
            appKey.set(exchange.getRequestHeaders().getFirst("DD-APPLICATION-KEY"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            entered.countDown();
            try {
                if (blocked.get()) release.await(5, TimeUnit.SECONDS);
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/steal");
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        client = new DatadogLogsClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/logs/events/search"));
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        client.close();
        server.stop(0);
        serverWorkers.shutdownNow();
        assertTrue(serverWorkers.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void fixedTypedRequestUsesHeadersAndReturnsOnlyScopedRedactedEvidence() {
        response.set(body("test", "orders", "failed password=hunter2 " + CONFIG.apiKey()
                + " Bearer tokenvalue https://user:secret@example.invalid/path?token=value owner@example.invalid"));
        var result = client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {});
        assertEquals(1, result.rows());
        assertFalse(result.truncated());
        var sent = json.readTree(requestBody.get());
        assertEquals("POST", requestMethod.get());
        assertEquals("/api/v2/logs/events/search", requestPath.get());
        assertEquals(3, sent.size());
        assertEquals("env:\"test\" AND service:\"orders\"", sent.path("filter").path("query").asText());
        assertEquals(FROM.toString(), sent.path("filter").path("from").asText());
        assertEquals(TO.toString(), sent.path("filter").path("to").asText());
        assertEquals("fixture-index", sent.path("filter").path("indexes").get(0).asText());
        assertEquals(2, sent.path("page").path("limit").asInt());
        assertEquals(CONFIG.apiKey(), apiKey.get());
        assertEquals(CONFIG.applicationKey(), appKey.get());
        for (String secret : List.of("hunter2", CONFIG.apiKey(), "tokenvalue", "user:secret", "owner@example.invalid", "private-host", "arbitrary-secret")) {
            assertFalse(result.output().contains(secret));
        }
        assertTrue(result.output().contains("[REDACTED"));
        assertTrue(json.readTree(result.output()).path("redacted").asBoolean());
        assertEquals(1, calls.get());
    }

    @Test
    void disallowedEnvironmentServiceTimeLimitAndQuerySyntaxNeverReachServer() {
        for (var query : List.of(new DatadogLogsClient.Query("prod", "orders", FROM, TO, 2),
                new DatadogLogsClient.Query("test", "payments", FROM, TO, 2),
                new DatadogLogsClient.Query("test", "orders", FROM.minusSeconds(1), TO, 2),
                new DatadogLogsClient.Query("test", "orders", FROM, TO.plusSeconds(1), 2),
                new DatadogLogsClient.Query("test", "orders", FROM, TO, 3))) {
            assertThrows(IllegalArgumentException.class, () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query, () -> {}));
        }
        for (String scope : List.of("*", "test OR env:prod", "orders\"", "@password", "https://evil.invalid")) {
            assertThrows(IllegalArgumentException.class, () -> new DatadogLogsClient.Query(scope, "orders", FROM, TO, 1));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void responseScopeMismatchFailsWithoutReturningPartialEvidence() {
        for (String body : List.of(body("prod", "orders", "private"), body("test", "payments", "private"),
                body("test", "orders", "private").replace("[\"env:test\"]", "[\"env:test\",\"env:prod\"]"),
                body("test", "orders", "private").replace("2025-01-01T00:01:00Z", "2024-01-01T00:01:00Z"))) {
            response.set(body);
            var error = assertThrows(LibraryException.class, () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {}));
            assertEquals(502, error.status());
            assertFalse(error.getMessage().contains("private"));
        }
    }

    @Test
    void redirectAndAuthenticationErrorsAreNotFollowedRetriedOrEchoed() {
        response.set(CONFIG.apiKey() + CONFIG.applicationKey());
        for (int code : List.of(302, 401, 403, 429, 500)) {
            status.set(code);
            var error = assertThrows(LibraryException.class, () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {}));
            assertFalse(error.getMessage().contains(CONFIG.apiKey()));
            assertNull(error.getCause());
        }
        assertEquals(5, calls.get());
    }

    @Test
    void paginationIsDisclosedWithoutFollowingNextLinks() {
        response.set(body("test", "orders", "fixture").replace("\"meta\":{}", "\"meta\":{\"page\":{\"after\":\"opaque\"}}"));
        var result = client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {});
        assertTrue(result.truncated());
        assertTrue(json.readTree(result.output()).path("truncated").asBoolean());
        assertEquals(1, calls.get());
    }

    @Test
    void bodyAndRenderedByteLimitsAreEnforced() {
        response.set(body("test", "orders", "가".repeat(5000)));
        var result = client.read(CONFIG, policy(512, Duration.ofSeconds(2)), query(), () -> {});
        assertTrue(result.truncated());
        assertTrue(result.output().getBytes(StandardCharsets.UTF_8).length <= 512);
        response.set("x".repeat(1_048_577));
        assertThrows(LibraryException.class, () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {}));
    }

    @Test
    void emptyAndMalformedResponsesAreDistinct() {
        response.set("{\"data\":[]}");
        assertEquals("", client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {}).output());
        response.set("{\"error\":\"private\"}");
        assertThrows(LibraryException.class, () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {}));
        response.set("{\"data\":[],\"meta\":{\"status\":\"timeout\"}}");
        assertEquals(504, assertThrows(LibraryException.class,
                () -> client.read(CONFIG, policy(4096, Duration.ofSeconds(2)), query(), () -> {})).status());
    }

    @Test
    void cancellationInterruptsBlockedHttpRead() throws Exception {
        blocked.set(true);
        var cancelled = new AtomicBoolean();
        var future = CompletableFuture.supplyAsync(() -> client.read(CONFIG, policy(4096, Duration.ofSeconds(4)), query(), () -> {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        cancelled.set(true);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
    }

    @Test
    void deadlineCancelsBlockedHttpRead() {
        blocked.set(true);
        assertTimeout(Duration.ofSeconds(2), () -> assertEquals(504, assertThrows(LibraryException.class,
                () -> client.read(CONFIG, policy(4096, Duration.ofMillis(300)), query(), () -> {})).status()));
    }

    @Test
    void redactorHandlesHeadersQuotedValuesKnownKeysAndPrivateKeys() {
        String text = "password=\"has spaces\" token='private value'\nCookie: session=one; other=two\nAuthorization: Basic abc123\n"
                + "-----BEGIN PRIVATE KEY-----\nsecret material\n-----END PRIVATE KEY-----\n" + CONFIG.applicationKey();
        String redacted = DatadogLogRedactor.redact(text, List.of(CONFIG.applicationKey()));
        for (String secret : List.of("has spaces", "private value", "session=one", "other=two", "abc123", "secret material", CONFIG.applicationKey())) {
            assertFalse(redacted.contains(secret));
        }
        assertEquals("", DatadogLogRedactor.redact(null, List.of()));
    }

    private DatadogLogsClient.Policy policy(int bytes, Duration timeout) {
        return new DatadogLogsClient.Policy(Map.of("test", Set.of("orders")), Set.of("fixture-index"), FROM, TO,
                Duration.ofHours(1), 2, bytes, timeout);
    }

    private DatadogLogsClient.Query query() { return new DatadogLogsClient.Query("test", "orders", FROM, TO, 2); }

    private String body(String env, String service, String message) {
        return json.writeValueAsString(Map.of("data", List.of(Map.of("id", "fixture-id", "attributes", Map.of("timestamp", "2025-01-01T00:01:00Z",
                "tags", List.of("env:" + env), "service", service, "message", message, "host", "private-host",
                "attributes", Map.of("password", "arbitrary-secret")))), "meta", Map.of()));
    }
}
