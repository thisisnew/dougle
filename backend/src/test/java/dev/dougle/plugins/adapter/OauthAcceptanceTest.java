package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "forge.acceptance.oauth", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OauthAcceptanceTest {
    @TempDir Path directory;
    private final JsonMapper json = new JsonMapper();
    private final List<Map<String, Object>> results = new ArrayList<>();
    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/oauth-acceptance-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) for (int action = 0; action < 5; action++) {
            int selectedFixture = fixture;
            int selectedAction = action;
            String id = "T%04d".formatted(526 + fixture * 5 + action);
            tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
        }
        return tests.stream();
    }
    private void execute(String id, int fixture, int action) throws Exception {
        var record = new LinkedHashMap<String, Object>();
        record.put("id", id); record.put("startedAt", Instant.now().toString());
        record.put("scope", "REAL_LOOPBACK_OAUTH_CALLBACK_PKCE_VAULT_MOCKED_PROVIDER_AND_BROWSER");
        record.put("fullAcceptancePassed", false);
        record.put("remaining", List.of("Actual browser/provider account consent and document read", "Full app restart and UI lifecycle", "Wall-clock expired callback not tested; invalid state tested"));
        long started = System.nanoTime();
        BrowserProvider provider = fixture == 2 ? BrowserProvider.ATLASSIAN : BrowserProvider.NOTION;
        var vault = new PluginVault(Files.createDirectory(directory.resolve(id)).toString());
        var http = mock(BrowserPluginHttp.class);
        var browser = mock(PluginBrowser.class);
        var authorization = new AtomicReference<URI>();
        var exchanges = new AtomicInteger();
        doAnswer(invocation -> { authorization.set(invocation.getArgument(1)); return null; }).when(browser).open(any(), any());
        when(http.send(any(), anyString(), any(), nullable(String.class), anyBoolean(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    URI uri = invocation.getArgument(2);
                    Object result;
                    if (uri.getPath().contains("well-known")) result = Map.of("issuer", provider.origin, "authorization_endpoint", provider.origin + "/authorize",
                            "token_endpoint", provider.origin + "/token", "registration_endpoint", provider.origin + "/register", "code_challenge_methods_supported", List.of("S256"));
                    else if (uri.getPath().equals("/register")) result = Map.of("client_id", "synthetic-client", "token_endpoint_auth_method", "none");
                    else if (uri.getPath().equals("/token")) {
                        exchanges.incrementAndGet();
                        var fields = BrowserPluginConnections.query(invocation.getArgument(3));
                        assertThat(fields.get("resource")).isEqualTo(provider.resource());
                        if (fields.get("grant_type").equals("authorization_code")) assertThat(BrowserPluginConnections.challenge(fields.get("code_verifier")))
                                .isEqualTo(BrowserPluginConnections.query(authorization.get().getRawQuery()).get("code_challenge"));
                        result = Map.of("access_token", "synthetic-access", "refresh_token", "synthetic-rotated", "token_type", "Bearer", "expires_in", 3600);
                    } else {
                        var payload = json.readTree((String) invocation.getArgument(3));
                        String method = payload.path("method").asText();
                        Object response = method.equals("initialize") ? Map.of("protocolVersion", "2025-03-26")
                                : method.equals("tools/list") ? Map.of("tools", List.of(Map.of("name", provider == BrowserProvider.NOTION ? "notion-fetch" : "getAccessibleAtlassianResources", "inputSchema", Map.of()))) : Map.of();
                        String requestId = invocation.getArgument(7);
                        result = requestId == null ? Map.of() : Map.of("id", requestId, "result", response);
                    }
                    return new BrowserPluginHttp.Reply(json.valueToTree(result), "synthetic-session");
                });
        var connections = new BrowserPluginConnections(vault, http, browser);
        try {
            if (fixture == 4) {
                vault.put("browser-NOTION", json.writeValueAsString(new BrowserPluginConnections.Grant("expired", "synthetic-refresh", 1, provider.origin + "/token", "synthetic-client")));
                if (action == 1) {
                    doThrow(new LibraryException(401, "synthetic expired refresh")).when(http).send(any(), eq("POST"), eq(URI.create(provider.origin + "/token")), anyString(), eq(true), isNull(), isNull(), isNull());
                    assertThatThrownBy(() -> connections.accessToken(provider)).isInstanceOf(LibraryException.class).hasMessageNotContaining("synthetic-refresh");
                    assertThat(connections.connected(provider)).isFalse();
                } else {
                    assertThat(connections.accessToken(provider)).isEqualTo("synthetic-access");
                    assertThat(connections.accessToken(provider)).isEqualTo("synthetic-access");
                    assertThat(exchanges.get()).isEqualTo(1);
                    var saved = json.readValue(vault.get("browser-NOTION"), BrowserPluginConnections.Grant.class);
                    assertThat(saved.refresh()).isEqualTo("synthetic-rotated");
                }
            } else {
                var attempt = connections.start(provider);
                for (int retry = 0; authorization.get() == null && retry < 150; retry++) Thread.sleep(20);
                assertThat(authorization.get()).isNotNull();
                var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
                assertThat(params.get("code_challenge_method")).isEqualTo("S256");
                if (action == 2) assertThatThrownBy(() -> connections.start(provider)).isInstanceOf(LibraryException.class);
                var query = new LinkedHashMap<String, String>();
                query.put("state", fixture == 3 ? "wrong-state" : params.get("state")); query.put("code", "synthetic-code");
                if (fixture == 1) query.put("error", "access_denied");
                try (var client = HttpClient.newHttpClient()) {
                    var reply = client.send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri") + "?" + BrowserPluginHttp.form(query)))
                            .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(reply.body()).doesNotContain("synthetic-access", "synthetic-code", params.get("state"));
                    if (fixture == 1 || fixture == 3) {
                        assertThat(reply.statusCode()).isEqualTo(fixture == 1 ? 200 : 400);
                        if (fixture == 1) assertThat(connections.progress(provider, attempt.id()).state()).isEqualTo("CANCELLED");
                        assertThat(connections.connected(provider)).isFalse();
                        assertThat(exchanges.get()).isZero();
                        connections.cancel(provider, attempt.id());
                    } else {
                        assertThat(reply.statusCode()).isEqualTo(200);
                        assertThat(connections.accessToken(provider)).isEqualTo("synthetic-access");
                        assertThat(exchanges.get()).isEqualTo(1);
                    }
                }
                if (action == 3 && (fixture == 1 || fixture == 3)) assertThat(connections.start(provider).state()).isEqualTo("STARTING");
            }
            if (action == 4) {
                var reopened = new BrowserPluginConnections(new PluginVault(directory.resolve(id).toString()), http, browser);
                try {
                    assertThat(reopened.connected(provider)).isEqualTo(connections.connected(provider));
                    if (reopened.connected(provider)) assertThat(reopened.accessToken(provider)).isEqualTo("synthetic-access");
                } finally { reopened.close(); }
            }
            record.put("status", "PARTIAL"); record.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            record.put("status", "FAIL"); record.put("checkStatus", "FAIL"); record.put("failure", failure.toString()); throw failure;
        } finally { connections.close(); record.put("tokenExchanges", exchanges.get()); record.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); results.add(record); }
    }
}
