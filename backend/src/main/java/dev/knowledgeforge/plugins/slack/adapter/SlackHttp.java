package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.slack.application.SlackProvider;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SlackHttp {
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient client = HttpClients.custom()
            .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(10)).build()).build();
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    public JsonNode post(String method, String token) {
        if (!java.util.Set.of("auth.test", "apps.connections.open").contains(method)) throw new IllegalArgumentException("Unsupported Slack API");
        if (token == null || token.length() > 2048 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid Slack token");
        var request = new HttpPost("https://slack.com/api/" + method);
        request.setHeader("Authorization", "Bearer " + token);
        request.setHeader("Accept", "application/json");
        var deadline = deadlines.schedule(request::cancel, 15, TimeUnit.SECONDS);
        try {
            return client.execute(request, response -> {
                if (response.getCode() != 200 || response.getEntity() == null) throw new IllegalArgumentException();
                try (var input = response.getEntity().getContent()) {
                    byte[] bytes = input.readNBytes(SlackEnvelopeParser.MAX_BYTES + 1);
                    if (bytes.length > SlackEnvelopeParser.MAX_BYTES) throw new IllegalArgumentException();
                    var value = json.readTree(bytes);
                    if (!value.isObject() || !value.path("ok").isBoolean()) throw new IllegalArgumentException();
                    if (!value.path("ok").asBoolean()) {
                        String error = value.path("error").asString("");
                        if (java.util.Set.of("invalid_auth", "not_authed", "token_revoked", "account_inactive", "token_expired").contains(error)) {
                            throw new SlackProvider.AccessFailure(SlackProvider.FailureKind.AUTH_REQUIRED);
                        }
                        if (java.util.Set.of("missing_scope", "no_permission", "not_allowed_token_type").contains(error)) {
                            throw new SlackProvider.AccessFailure(SlackProvider.FailureKind.PERMISSION_DENIED);
                        }
                        throw new IllegalArgumentException();
                    }
                    return value;
                }
            });
        } catch (SlackProvider.AccessFailure exception) { throw exception; }
        catch (Exception exception) { throw new LibraryException(502, "Slack 인증·권한 또는 네트워크를 확인하세요."); }
        finally { deadline.cancel(false); }
    }

    @PreDestroy public void close() throws Exception { deadlines.shutdownNow(); client.close(); }
}
