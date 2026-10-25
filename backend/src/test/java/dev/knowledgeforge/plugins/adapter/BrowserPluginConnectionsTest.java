package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserPluginConnectionsTest {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();
    BrowserPluginHttp http;
    PluginBrowser browser;
    PluginVault vault;
    BrowserPluginConnections connections;
    final AtomicReference<URI> authorization = new AtomicReference<>();
    final String access = "synthetic-access-token";
    @BeforeEach void setup() {
        http = mock(BrowserPluginHttp.class); browser = mock(PluginBrowser.class); vault = new PluginVault(directory.toString());
        connections = new BrowserPluginConnections(vault, http, browser);
        doAnswer(invocation -> { authorization.set(invocation.getArgument(1)); return null; }).when(browser).open(any(), any());
        when(http.send(any(), anyString(), any(), nullable(String.class), anyBoolean(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    BrowserProvider provider = invocation.getArgument(0);
                    URI uri = invocation.getArgument(2);
                    String body = invocation.getArgument(3);
                    String id = invocation.getArgument(7);
                    Object result;
                    if (uri.getPath().contains("well-known")) result = Map.of("issuer", provider.origin, "authorization_endpoint", provider.origin + "/authorize",
                            "token_endpoint", provider.origin + "/token", "registration_endpoint", provider.origin + "/register", "code_challenge_methods_supported", List.of("S256"));
                    else if (uri.getPath().equals("/register")) result = Map.of("client_id", "synthetic-client", "token_endpoint_auth_method", "none");
                    else if (uri.getPath().equals("/token")) result = Map.of("access_token", access, "refresh_token", "synthetic-refresh", "token_type", "Bearer", "expires_in", 3600);
                    else {
                        var payload = json.readTree(body);
                        Object response = switch (payload.path("method").asText()) {
                            case "initialize" -> Map.of("protocolVersion", "2025-03-26");
                            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "notion-fetch", "inputSchema", Map.of("required", List.of("id"))),
                                    Map.of("name", "notion-delete-page", "inputSchema", Map.of())));
                            default -> Map.of();
                        };
                        result = id == null ? Map.of() : Map.of("jsonrpc", "2.0", "id", id, "result", response);
                    }
                    return new BrowserPluginHttp.Reply(json.valueToTree(result), "synthetic-session");
                });
    }
    @AfterEach void close() { connections.close(); }
    Map<String, String> begin() throws Exception {
        connections.start(BrowserProvider.NOTION);
        for (int retries = 0; authorization.get() == null && retries < 100; retries++) Thread.sleep(20);
        assertThat(authorization.get()).isNotNull();
        return BrowserPluginConnections.query(authorization.get().getRawQuery());
    }
    HttpResponse<String> callback(Map<String, String> params, Map<String, String> changes) throws Exception {
        var query = new LinkedHashMap<String, String>(); query.put("state", params.get("state")); query.put("code", "synthetic-code"); query.putAll(changes);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri") + "?" + BrowserPluginHttp.form(query))).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void browserApprovalValidatesStatePkceAndStoresOnlyAfterHandshake() throws Exception {
        var params = begin();
        assertThat(params).containsEntry("code_challenge_method", "S256").containsEntry("client_id", "synthetic-client");
        assertThat(params.get("state")).hasSize(43);
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
        assertThat(callback(params, Map.of("state", "incorrect")).statusCode()).isEqualTo(400);
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
        var response = callback(params, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Dougle로 돌아가").doesNotContain(access, "synthetic-refresh");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(connections.accessToken(BrowserProvider.NOTION)).isEqualTo(access);
        verify(http).send(eq(BrowserProvider.NOTION), eq("POST"), eq(URI.create("https://mcp.notion.com/token")), argThat(body -> {
            var fields = BrowserPluginConnections.query(body);
            try { return fields.get("code").equals("synthetic-code") && BrowserPluginConnections.challenge(fields.get("code_verifier")).equals(params.get("code_challenge")); }
            catch (Exception exception) { return false; }
        }), eq(true), isNull(), isNull(), isNull());
        var restarted = new BrowserPluginConnections(new PluginVault(directory.toString()), http, browser);
        try { assertThat(restarted.accessToken(BrowserProvider.NOTION)).isEqualTo(access); } finally { restarted.close(); }
    }
    @Test void denialAndCancelPreserveAnExistingConnection() throws Exception {
        vault.put("browser-NOTION", json.writeValueAsString(new BrowserPluginConnections.Grant(access, "refresh", Instant.now().getEpochSecond() + 3600, "https://mcp.notion.com/token", "client")));
        var params = begin();
        callback(params, Map.of("error", "access_denied"));
        assertThat(connections.accessToken(BrowserProvider.NOTION)).isEqualTo(access);
        authorization.set(null);
        var next = connections.start(BrowserProvider.NOTION);
        connections.cancel(BrowserProvider.NOTION, next.id());
        assertThat(connections.progress(BrowserProvider.NOTION, next.id()).state()).isEqualTo("CANCELLED");
        assertThat(connections.accessToken(BrowserProvider.NOTION)).isEqualTo(access);
        connections.disconnect(BrowserProvider.NOTION);
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
    }
    @Test void cancellationDuringExchangeCannotSaveLateCredentials() throws Exception {
        var params = begin();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS);
            return new BrowserPluginHttp.Reply(json.readTree("{\"access_token\":\"late\",\"token_type\":\"Bearer\"}"), null);
        }).when(http).send(any(), eq("POST"), eq(URI.create("https://mcp.notion.com/token")), anyString(), eq(true), isNull(), isNull(), isNull());
        var response = CompletableFuture.runAsync(() -> { try { callback(params, Map.of()); } catch (Exception ignored) { } });
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        connections.disconnect(BrowserProvider.NOTION);
        release.countDown(); response.get(5, TimeUnit.SECONDS);
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
    }
    @Test void parallelRefreshUsesOneRotatedTokenAndDisconnectClearsIt() throws Exception {
        vault.put("browser-NOTION", json.writeValueAsString(new BrowserPluginConnections.Grant("expired", "refresh", 1, "https://mcp.notion.com/token", "client")));
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> connections.accessToken(BrowserProvider.NOTION));
            var second = workers.submit(() -> connections.accessToken(BrowserProvider.NOTION));
            assertThat(first.get()).isEqualTo(access); assertThat(second.get()).isEqualTo(access);
        }
        verify(http, times(1)).send(any(), eq("POST"), eq(URI.create("https://mcp.notion.com/token")), anyString(), eq(true), isNull(), isNull(), isNull());
        assertThat(vault.get("browser-NOTION")).contains("synthetic-refresh");
        connections.disconnect(BrowserProvider.NOTION);
        assertThatThrownBy(() -> connections.accessToken(BrowserProvider.NOTION)).hasMessageContaining("연결이 필요");
    }
    @Test void invalidRefreshClearsGrantButNetworkFailureDoesNot() {
        vault.put("browser-NOTION", json.writeValueAsString(new BrowserPluginConnections.Grant("expired", "refresh", 1, "https://mcp.notion.com/token", "client")));
        doThrow(new LibraryException(502, "network")).when(http).send(any(), anyString(), any(), anyString(), eq(true), isNull(), isNull(), isNull());
        assertThatThrownBy(() -> connections.accessToken(BrowserProvider.NOTION)).hasMessage("network");
        assertThat(connections.connected(BrowserProvider.NOTION)).isTrue();
        doThrow(new LibraryException(401, "expired")).when(http).send(any(), anyString(), any(), anyString(), eq(true), isNull(), isNull(), isNull());
        assertThatThrownBy(() -> connections.accessToken(BrowserProvider.NOTION)).hasMessage("expired");
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
    }
    @Test void rejectsForeignEndpointsDuplicateParametersAndWriteTools() {
        for (String uri : List.of("http://mcp.notion.com/token", "https://mcp.notion.com.evil.test/token", "https://user@mcp.notion.com/token", "https://127.0.0.1/token", "https://mcp.atlassian.com/token"))
            assertThatThrownBy(() -> BrowserProvider.NOTION.trusted(uri)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> BrowserPluginConnections.query("state=first&state=second")).isInstanceOf(LibraryException.class);
        var session = new PluginMcpSession(BrowserProvider.NOTION, http, access);
        assertThatThrownBy(() -> session.call("notion-delete-page", Map.of("id", "fixture"))).hasMessageContaining("허용되지");
    }
    @Test void browserFailureIsRetryableAndDoesNotConnect() throws Exception {
        doThrow(new LibraryException(502, "브라우저를 열지 못했습니다.")).when(browser).open(any(), any());
        var attempt = connections.start(BrowserProvider.NOTION);
        for (int retries = 0; !connections.progress(BrowserProvider.NOTION, attempt.id()).state().equals("FAILED") && retries < 100; retries++) Thread.sleep(20);
        assertThat(connections.progress(BrowserProvider.NOTION, attempt.id()).state()).isEqualTo("FAILED");
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
    }
    @Test void streamingResponsesKeepUnicodeAndIgnoreOtherIds() throws Exception {
        try (var input = new java.io.ByteArrayInputStream(("data: {\"id\":\"other\",\"result\":{}}\n\n" +
                "data: {\"id\":\"wanted\",\"result\":{\"text\":\"가상 문서\"}}\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var transport = new BrowserPluginHttp();
            try { assertThat(transport.readEvent(input, "wanted").path("result").path("text").asText()).isEqualTo("가상 문서"); }
            finally { transport.close(); }
        }
    }
    @Test void pendingLoginExpiresAndCanBeRetried() throws Exception {
        connections.close();
        connections = new BrowserPluginConnections(vault, http, browser, 100);
        var attempt = connections.start(BrowserProvider.NOTION);
        for (int retries = 0; !connections.progress(BrowserProvider.NOTION, attempt.id()).state().equals("EXPIRED") && retries < 100; retries++) Thread.sleep(20);
        assertThat(connections.progress(BrowserProvider.NOTION, attempt.id()).state()).isEqualTo("EXPIRED");
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
        assertThat(connections.start(BrowserProvider.NOTION).id()).isNotEqualTo(attempt.id());
    }
    @Test void oversizedCredentialWritePreservesExistingVault() {
        vault.put("existing", "synthetic-secret");
        assertThatThrownBy(() -> vault.put("oversized", "x".repeat(65536))).isInstanceOf(LibraryException.class);
        assertThat(vault.get("existing")).isEqualTo("synthetic-secret");
        assertThat(vault.get("oversized")).isNull();
    }
    @Test void tokenExchangeAndRefreshKeepTheAuthorizedResource() throws Exception {
        var params = begin();
        assertThat(params).containsEntry("resource", BrowserProvider.NOTION.resource());
        assertThat(callback(params, Map.of()).statusCode()).isEqualTo(200);
        verify(http).send(eq(BrowserProvider.NOTION), eq("POST"), eq(URI.create("https://mcp.notion.com/token")),
                argThat(body -> BrowserPluginConnections.query(body).get("resource").equals(params.get("resource"))), eq(true), isNull(), isNull(), isNull());
        vault.put("browser-NOTION", json.writeValueAsString(new BrowserPluginConnections.Grant("expired", "refresh", 1, "https://mcp.notion.com/token", "client")));
        connections.accessToken(BrowserProvider.NOTION);
        verify(http, times(2)).send(eq(BrowserProvider.NOTION), eq("POST"), eq(URI.create("https://mcp.notion.com/token")),
                argThat(body -> BrowserPluginConnections.query(body).get("resource").equals(params.get("resource"))), eq(true), isNull(), isNull(), isNull());
    }
    @Test void callbackShowsEscapedStageFailureAndAllowsRetryWithoutSavingGrant() throws Exception {
        var params = begin();
        doThrow(new LibraryException(502, "가상 오류 <test> (HTTP 400)"))
                .when(http).send(any(), eq("POST"), eq(URI.create("https://mcp.notion.com/token")), anyString(), eq(true), isNull(), isNull(), isNull());
        var response = callback(params, Map.of());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("인증 승인 확인", "&lt;test&gt;", "HTTP 400").doesNotContain("<test>", "synthetic-code", params.get("state"));
        assertThat(connections.connected(BrowserProvider.NOTION)).isFalse();
        assertThat(connections.start(BrowserProvider.NOTION).state()).isEqualTo("STARTING");
    }
}
