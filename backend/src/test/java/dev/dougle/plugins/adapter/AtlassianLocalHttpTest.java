package dev.dougle.plugins.adapter;

import com.sun.net.httpserver.HttpServer;
import dev.dougle.library.application.LibraryException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtlassianLocalHttpTest {
    @TempDir Path directory;
    final JsonMapper json = new JsonMapper();
    final BrowserProvider provider = BrowserProvider.ATLASSIAN;
    final AtomicReference<URI> authorization = new AtomicReference<>();
    final List<String> reads = new CopyOnWriteArrayList<>();
    final List<String> cloudIds = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> exchanges = new CopyOnWriteArrayList<>();
    HttpServer server;
    BrowserPluginHttp http;
    BrowserPluginConnections connections;
    PluginVault vault;
    volatile String fault = "";
    volatile String product = "both";
    volatile String failedEndpoint;
    volatile int endpointStatus;
    volatile boolean omitScope;
    volatile List<Map<String, String>> siteOverrides;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals(failedEndpoint)) {
                byte[] failure = "<html>synthetic-secret-must-not-leak</html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(endpointStatus, failure.length);
                exchange.getResponseBody().write(failure);
                exchange.close();
                return;
            }
            Object response;
            int status = 200;
            if (path.contains("well-known")) {
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("issuer", provider.origin);
                metadata.put("authorization_endpoint", provider.origin + "/v1/authorize");
                metadata.put("token_endpoint", provider.origin + "/v1/token");
                metadata.put("code_challenge_methods_supported", List.of("S256"));
                if (!fault.equals("setup")) metadata.put("registration_endpoint", provider.origin + "/v1/register");
                response = metadata;
            } else if (path.equals("/v1/register")) {
                response = Map.of("client_id", "synthetic-client", "token_endpoint_auth_method", "none");
            } else if (path.equals("/v1/token")) {
                exchanges.add(BrowserPluginConnections.query(body));
                if (Set.of("invalid_grant", "invalid_client", "invalid_scope", "unavailable").contains(fault)) {
                    status = fault.equals("unavailable") ? 503 : 400;
                    response = Map.of("error", fault, "error_description", "synthetic-secret-must-not-leak");
                } else {
                    var grant = new LinkedHashMap<String, Object>();
                    grant.put("access_token", "synthetic-access"); grant.put("refresh_token", "synthetic-rotated");
                    grant.put("token_type", "Bearer"); grant.put("expires_in", 3600);
                    if (!omitScope) grant.put("scope", fault.equals("scope") ? "write:jira:agent-interface"
                            : product.equals("jira") ? "read:jira:agent-interface" : product.equals("confluence")
                            ? "read:confluence:agent-interface" : "read:jira:agent-interface read:confluence:agent-interface");
                    response = grant;
                }
            } else {
                var request = json.readTree(body);
                String method = request.path("method").asText();
                Object result = switch (method) {
                    case "initialize" -> Map.of("protocolVersion", "2025-03-26");
                    case "tools/list" -> {
                        var available = new ArrayList<Map<String, Object>>();
                        if (!fault.equals("no-discovery")) available.add(tool("getAccessibleAtlassianResources"));
                        if (!fault.equals("tools")) {
                            if (!product.equals("confluence")) available.add(tool("getJiraIssue", "cloudId", "issueIdOrKey"));
                            if (!product.equals("jira")) available.add(tool("getConfluenceContent", "cloudId", "contentId"));
                        }
                        yield Map.of("tools", available);
                    }
                    case "tools/call" -> {
                        String name = request.path("params").path("name").asText();
                        reads.add(name);
                        if (!name.equals("getAccessibleAtlassianResources")) cloudIds.add(request.path("params").path("arguments").path("cloudId").asText());
                        yield name.equals("getAccessibleAtlassianResources")
                                ? Map.of("structuredContent", Map.of("resources", siteOverrides != null ? siteOverrides : switch (fault) {
                                    case "sites" -> List.of();
                                    case "malformed-sites" -> "not-an-array";
                                    case "empty-resource" -> List.of(Map.of());
                                    case "invalid-site" -> List.of(Map.of("url", "https://fixture.atlassian.net.evil.test", "id", "fixture-cloud"));
                                    case "missing-cloud" -> List.of(Map.of("url", "https://fixture.atlassian.net"));
                                    default -> List.of(Map.of("url", "https://fixture.atlassian.net", "id", "fixture-cloud"));
                                }))
                                : Map.of("content", List.of(Map.of("type", "text", "text", "가상 회사 문서 본문")));
                    }
                    default -> Map.of();
                };
                response = request.has("id") ? Map.of("jsonrpc", "2.0", "id", request.path("id").asText(), "result", result) : Map.of();
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        http = new BrowserPluginHttp(uri -> URI.create("http://127.0.0.1:" + server.getAddress().getPort() + uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())));
        vault = new PluginVault(directory.toString());
        var browser = mock(PluginBrowser.class);
        doAnswer(call -> { authorization.set(call.getArgument(1)); return null; }).when(browser).open(any(), any());
        connections = new BrowserPluginConnections(vault, http, browser);
    }

    @AfterEach void close() throws Exception { connections.close(); http.close(); server.stop(0); }

    Map<String, Object> tool(String name, String... required) {
        var properties = new LinkedHashMap<String, Object>();
        for (String field : required) properties.put(field, Map.of("type", "string"));
        return Map.of("name", name, "inputSchema", Map.of("required", List.of(required), "properties", properties));
    }

    BrowserPluginConnections.Progress begin() throws Exception {
        var progress = connections.start(provider);
        for (int retry = 0; retry < 150 && authorization.get() == null
                && !connections.progress(provider, progress.id()).state().equals("FAILED"); retry++) Thread.sleep(20);
        return connections.progress(provider, progress.id());
    }

    HttpResponse<String> callback(String state) throws Exception {
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri") + "?"
                    + BrowserPluginHttp.form(Map.of("state", state, "code", "synthetic-code"))))
                    .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void approvalPkceCloudReadsAndRefreshUseRealLocalHttp() throws Exception {
        var progress = begin();
        assertThat(progress.state()).isEqualTo("WAITING");
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(params.get("scope")).isEqualTo("read:jira:agent-interface read:confluence:agent-interface");
        assertThat(callback("wrong-state").statusCode()).isEqualTo(400);
        assertThat(exchanges).isEmpty();
        assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        assertThat(connections.connected(provider)).isTrue();
        assertThat(BrowserPluginConnections.challenge(exchanges.getFirst().get("code_verifier"))).isEqualTo(params.get("code_challenge"));
        assertThat(exchanges.getFirst().get("resource")).isEqualTo(provider.resource());
        var reader = new BrowserPluginReader(connections, http);
        for (String path : List.of("/browse/TEST-1", "/wiki/spaces/TEST/pages/123/Page", "/wiki/pages/viewpage.action?pageId=123")) {
            try (var snapshot = reader.collect(provider, "https://fixture.atlassian.net" + path)) {
                assertThat(new String(snapshot.files().getFirst().content(), StandardCharsets.UTF_8)).contains("가상 회사 문서 본문");
            }
        }
        assertThat(reads).contains("getJiraIssue", "getConfluenceContent");
        int before = reads.size();
        assertThatThrownBy(() -> reader.collect(provider, "https://wrong.atlassian.net/browse/TEST-1")).hasMessageContaining("접근 권한");
        assertThat(reads.subList(before, reads.size())).containsExactly("getAccessibleAtlassianResources");
        expire();
        assertThat(connections.accessToken(provider)).isEqualTo("synthetic-access");
        assertThat(exchanges.getLast()).containsEntry("refresh_token", "synthetic-old-refresh").containsEntry("resource", provider.resource());
        assertThat(json.readValue(vault.get("browser-ATLASSIAN"), BrowserPluginConnections.Grant.class).refresh()).isEqualTo("synthetic-rotated");
    }

    @ParameterizedTest @ValueSource(strings = {"scope", "tools", "sites", "malformed-sites", "empty-resource", "invalid-site", "missing-cloud", "no-discovery", "invalid_grant", "invalid_scope", "invalid_client"})
    void incompleteApprovalNeverConnects(String failure) throws Exception {
        begin();
        fault = failure;
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        var reply = callback(params.get("state"));
        assertThat(reply.statusCode()).isEqualTo(400);
        assertThat(reply.body()).doesNotContain("synthetic-secret-must-not-leak", "synthetic-access");
        assertThat(connections.connected(provider)).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"jira", "confluence"})
    void oneProductConnectsReadsAndRefreshesWithoutGrantingTheOther(String selected) throws Exception {
        product = selected;
        assertThat(begin().state()).isEqualTo("WAITING");
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        assertThat(connections.connected(provider)).isTrue();
        var reader = new BrowserPluginReader(connections, http);
        String jira = "https://fixture.atlassian.net/browse/TEST-1";
        String confluence = "https://fixture.atlassian.net/wiki/spaces/TEST/pages/123/Page";
        try (var snapshot = reader.collect(provider, selected.equals("jira") ? jira : confluence)) {
            assertThat(new String(snapshot.files().getFirst().content(), StandardCharsets.UTF_8)).contains("가상 회사 문서 본문");
        }
        reads.clear();
        assertThatThrownBy(() -> reader.collect(provider, selected.equals("jira") ? confluence : jira))
                .isInstanceOfSatisfying(LibraryException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(403);
                    assertThat(exception.getMessage()).contains(selected.equals("jira") ? "Confluence 읽기 권한" : "Jira 읽기 권한");
                });
        assertThat(reads).containsExactly("getAccessibleAtlassianResources");
        assertThat(connections.connected(provider)).isTrue();
        expire();
        assertThat(connections.accessToken(provider)).isEqualTo("synthetic-access");
        assertThat(connections.connected(provider)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"jira", "confluence"})
    void omittedScopeUsesAvailableProductCapabilities(String selected) throws Exception {
        product = selected; omitScope = true;
        begin();
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        assertThat(connections.connected(provider)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"malformed-sites", "empty-resource", "sites"})
    void malformedResourcesDuringReadDoNotDiscardExistingConnection(String failure) throws Exception {
        begin();
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        fault = failure; reads.clear();
        assertThatThrownBy(() -> new BrowserPluginReader(connections, http).collect(provider, "https://fixture.atlassian.net/browse/TEST-1"))
                .hasMessageContaining("사이트");
        assertThat(reads).containsExactly("getAccessibleAtlassianResources");
        assertThat(connections.connected(provider)).isTrue();
    }

    @Test void missingRegistrationExplainsUnavailableSetupWithoutOpeningBrowser() throws Exception {
        fault = "setup";
        var progress = begin();
        assertThat(progress.state()).isEqualTo("FAILED");
        assertThat(progress.message()).contains("자동 연결 등록", "Dougle 지원팀", "토큰 발급이나 개발자 설정은 필요하지 않습니다");
        assertThat(authorization.get()).isNull();
        assertThat(connections.connected(provider)).isFalse();
    }

    @Test void mixedResourcesConnectAndReadOnlyTheSupportedAuthorizedSite() throws Exception {
        var sites = new ArrayList<>(unsupportedSites());
        sites.add(0, Map.of("url", "https://fixture.atlassian.net", "id", "fixture-cloud"));
        siteOverrides = sites;
        begin();
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        var reader = new BrowserPluginReader(connections, http);
        for (String path : List.of("/browse/TEST-1", "/wiki/spaces/TEST/pages/123/Page")) {
            try (var snapshot = reader.collect(provider, "https://fixture.atlassian.net" + path)) {
                assertThat(new String(snapshot.files().getFirst().content(), StandardCharsets.UTF_8)).contains("가상 회사 문서 본문");
            }
        }
        assertThat(cloudIds).containsExactly("fixture-cloud", "fixture-cloud");
        reads.clear();
        assertThatThrownBy(() -> reader.collect(provider, "https://other.atlassian.net/browse/TEST-1")).hasMessageContaining("접근 권한");
        assertThat(reads).containsExactly("getAccessibleAtlassianResources");
        for (var unsupported : unsupportedSites()) {
            String url = unsupported.get("url");
            if (url == null || url.equals("https://fixture.atlassian.net")) continue;
            assertThatThrownBy(() -> reader.collect(provider, url + "/browse/TEST-1"));
        }
        assertThat(cloudIds).containsExactly("fixture-cloud", "fixture-cloud");
        assertThat(connections.connected(provider)).isTrue();
    }

    @Test void allUnsupportedResourcesFailClearlyWithoutSavingConnection() throws Exception {
        siteOverrides = unsupportedSites();
        begin();
        var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
        var response = callback(params.get("state"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("지원되는 회사 사이트가 없습니다").doesNotContain("synthetic-access");
        assertThat(connections.connected(provider)).isFalse();
        assertThat(cloudIds).isEmpty();
    }

    List<Map<String, String>> unsupportedSites() {
        return List.of(
                Map.of("url", "https://company.example", "id", "unsupported-cloud"),
                Map.of("url", "https://fixture.atlassian.net.evil.test", "id", "spoofed-cloud"),
                Map.of("url", "https://127.0.0.1", "id", "private-cloud"),
                Map.of("url", "http://fixture.atlassian.net", "id", "insecure-cloud"),
                Map.of("url", "https://user@fixture.atlassian.net", "id", "userinfo-cloud"),
                Map.of("url", "https://fixture.atlassian.net:443", "id", "port-cloud"),
                Map.of("url", "https://other.atlassian.net/wiki", "id", "path-cloud"),
                Map.of("url", "https://fixture.atlassian.net?site=other", "id", "query-cloud"),
                Map.of("url", "https://fixture.atlassian.net#other", "id", "fragment-cloud"),
                Map.of("url", "not a URI", "id", "malformed-cloud"),
                Map.of("url", "https://fixture.atlassian.net", "id", "!invalid"),
                Map.of());
    }

    @Test void occupiedCallbackPortRegistersANewRedirect() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            vault.put("browser-ATLASSIAN-client", json.writeValueAsString(new BrowserPluginConnections.Client("old-client", occupied.getLocalPort())));
            assertThat(begin().state()).isEqualTo("WAITING");
            var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
            assertThat(URI.create(params.get("redirect_uri")).getPort()).isNotEqualTo(occupied.getLocalPort());
            assertThat(params.get("client_id")).isEqualTo("synthetic-client");
            assertThat(callback(params.get("state")).statusCode()).isEqualTo(200);
        }
    }

    @Test void refreshRejectionClearsConnectionButOutagePreservesIt() {
        expire();
        fault = "unavailable";
        assertThatThrownBy(() -> connections.accessToken(provider)).isInstanceOf(LibraryException.class);
        assertThat(connections.connected(provider)).isTrue();
        fault = "invalid_grant";
        assertThatThrownBy(() -> connections.accessToken(provider)).hasMessageContaining("만료");
        assertThat(connections.connected(provider)).isFalse();
    }

    @Test void refreshWithoutEitherReadScopeRequiresReconnection() {
        expire(); fault = "scope";
        assertThatThrownBy(() -> connections.accessToken(provider)).hasMessageContaining("읽기 승인");
        assertThat(connections.connected(provider)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata:401", "metadata:403", "metadata:429", "metadata:503", "metadata:200",
            "registration:401", "registration:403", "registration:429", "registration:503", "registration:200",
            "token:401", "token:403", "token:429", "token:503", "token:200",
            "mcp:401", "mcp:403", "mcp:429", "mcp:503", "mcp:200"})
    void companyGatewayFailuresIdentifyStageAndPreserveExistingGrant(String scenario) throws Exception {
        String[] parts = scenario.split(":");
        String stage = switch (parts[0]) {
            case "metadata" -> "서비스 인증 설정 확인";
            case "registration" -> "앱 자동 연결 등록";
            case "token" -> "인증 승인 확인";
            default -> "자료 서비스 연결";
        };
        failedEndpoint = switch (parts[0]) {
            case "metadata" -> "/.well-known/oauth-authorization-server";
            case "registration" -> "/v1/register";
            case "token" -> "/v1/token";
            default -> URI.create(provider.origin + provider.path).getPath();
        };
        endpointStatus = Integer.parseInt(parts[1]);
        expire();
        String previousGrant = vault.get("browser-ATLASSIAN");
        var progress = begin();
        if (Set.of("token", "mcp").contains(parts[0])) {
            assertThat(progress.state()).isEqualTo("WAITING");
            var params = BrowserPluginConnections.query(authorization.get().getRawQuery());
            var reply = callback(params.get("state"));
            assertThat(reply.statusCode()).isEqualTo(400);
            assertThat(reply.body()).contains(stage).doesNotContain("synthetic-secret-must-not-leak", "synthetic-access");
        } else {
            assertThat(authorization.get()).isNull();
        }
        for (int retry = 0; retry < 150 && !connections.progress(provider, progress.id()).state().equals("FAILED"); retry++) Thread.sleep(20);
        var failed = connections.progress(provider, progress.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        String reason = switch (endpointStatus) {
            case 401 -> "다시 연결";
            case 403 -> "회사 관리자";
            case 429 -> "요청이 많습니다";
            default -> parts[0].equals("token") || endpointStatus == 200 ? "응답 형식" : "HTTP 503";
        };
        assertThat(failed.message()).startsWith(stage + ": ").contains(reason)
                .doesNotContain("synthetic-secret-must-not-leak", "synthetic-access", "synthetic-old-refresh");
        assertThat(vault.get("browser-ATLASSIAN")).isEqualTo(previousGrant);
        assertThat(reads).isEmpty();
        failedEndpoint = null;
        authorization.set(null);
        assertThat(begin().state()).isEqualTo("WAITING");
        var retryParams = BrowserPluginConnections.query(authorization.get().getRawQuery());
        assertThat(callback(retryParams.get("state")).statusCode()).isEqualTo(200);
        assertThat(vault.get("browser-ATLASSIAN")).isNotEqualTo(previousGrant);
        try (var snapshot = new BrowserPluginReader(connections, http).collect(provider,
                "https://fixture.atlassian.net/browse/TEST-1")) {
            assertThat(new String(snapshot.files().getFirst().content(), StandardCharsets.UTF_8)).contains("가상 회사 문서 본문");
        }
    }

    void expire() {
        vault.put("browser-ATLASSIAN", json.writeValueAsString(new BrowserPluginConnections.Grant("expired", "synthetic-old-refresh", 1,
                provider.origin + "/v1/token", "synthetic-client")));
    }
}
