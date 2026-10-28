package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginReadOnlyBoundaryTest {
    private final JsonMapper json = new JsonMapper();

    private String rpc(String method, Map<String, ?> params) {
        return json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "fixture", "method", method, "params", params));
    }

    private void validate(BrowserProvider provider, String body) {
        BrowserPluginHttp.validateReadOrAuthentication(provider, "POST", URI.create(provider.origin + provider.path),
                body, false, "synthetic-token", null, "fixture");
    }

    @Test void allowsOnlyFixedProviderReadToolsWithIdentifierArguments() {
        validate(BrowserProvider.NOTION, rpc("tools/call", Map.of("name", "notion-fetch", "arguments", Map.of("id", "fixture-id"))));
        validate(BrowserProvider.ATLASSIAN, rpc("tools/call", Map.of("name", "getAccessibleAtlassianResources", "arguments", Map.of())));
        validate(BrowserProvider.ATLASSIAN, rpc("tools/call", Map.of("name", "getJiraIssue", "arguments", Map.of("cloudId", "fixture-cloud", "issueIdOrKey", "TEST-1"))));
        validate(BrowserProvider.ATLASSIAN, rpc("tools/call", Map.of("name", "getConfluencePage", "arguments", Map.of("cloudId", "fixture-cloud", "pageId", "123"))));
    }

    @Test void writeToolsAndArbitraryRpcCannotReachTransportEvenWithApprovalArguments() {
        for (String tool : List.of("notion-create-pages", "notion-update-page", "notion-delete-page", "createJiraIssue", "addCommentToJiraIssue", "updateConfluencePage", "executeSql", "fetch")) {
            for (var provider : BrowserProvider.values()) {
                assertThatThrownBy(() -> validate(provider, rpc("tools/call", Map.of("name", tool, "arguments", Map.of("approved", true)))))
                        .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(403));
            }
        }
        for (String method : List.of("resources/write", "sampling/createMessage", "roots/list", "prompts/get", "delete", "tools/call;delete")) {
            assertThatThrownBy(() -> validate(BrowserProvider.NOTION, rpc(method, Map.of()))).isInstanceOf(LibraryException.class);
        }
    }

    @Test void allowedToolCannotSmuggleWriteArgumentsUrlsOrCrossProviderNames() {
        for (var arguments : List.of(Map.of("id", "fixture", "operation", "delete"), Map.of("id", "https://example.invalid/delete"),
                Map.of("id", "fixture", "approved", "true"), Map.of("id", "fixture", "body", "new content"))) {
            assertThatThrownBy(() -> validate(BrowserProvider.NOTION, rpc("tools/call", Map.of("name", "notion-fetch", "arguments", arguments))))
                    .isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> validate(BrowserProvider.NOTION, rpc("tools/call", Map.of("name", "getJiraIssue", "arguments", Map.of("cloudId", "fixture", "issueId", "1")))))
                .isInstanceOf(LibraryException.class);
    }

    @Test void methodPathBatchDuplicateKeysAndTrailingPayloadCannotBypassPolicy() {
        String read = rpc("tools/call", Map.of("name", "notion-fetch", "arguments", Map.of("id", "fixture")));
        for (String method : List.of("PUT", "PATCH", "DELETE", "CONNECT", "GET", "post")) {
            assertThatThrownBy(() -> BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, method,
                    URI.create("https://mcp.notion.com/mcp"), read, false, "synthetic", null, "fixture")).isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST",
                URI.create("https://mcp.notion.com/arbitrary-write"), read, false, null, null, null)).isInstanceOf(LibraryException.class);
        for (String invalid : List.of("[" + read + "]", read + read, read.replace("\"method\":", "\"method\":\"tools/list\",\"method\":"))) {
            assertThatThrownBy(() -> validate(BrowserProvider.NOTION, invalid)).isInstanceOf(LibraryException.class);
        }
    }

    @Test void authenticationAndSessionInitializationRemainAllowedWithoutBusinessMutation() {
        BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "GET",
                URI.create("https://mcp.notion.com/.well-known/oauth-authorization-server"), null, false, null, null, null);
        String registration = json.writeValueAsString(Map.of("client_name", "Dougle", "redirect_uris", List.of("http://127.0.0.1:12345/callback"),
                "grant_types", List.of("authorization_code", "refresh_token"), "response_types", List.of("code"), "token_endpoint_auth_method", "none"));
        BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST", URI.create("https://mcp.notion.com/register"),
                registration, false, null, null, null);
        for (var fields : List.of(Map.of("grant_type", "authorization_code", "client_id", "fixture", "code", "fixture-code", "redirect_uri", "http://127.0.0.1:12345/callback", "code_verifier", "synthetic", "resource", BrowserProvider.NOTION.resource()),
                Map.of("grant_type", "refresh_token", "client_id", "fixture", "refresh_token", "synthetic", "resource", BrowserProvider.NOTION.resource()))) {
            BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST", URI.create("https://mcp.notion.com/token"),
                    BrowserPluginHttp.form(fields), true, null, null, null);
        }
        validate(BrowserProvider.NOTION, rpc("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(), "clientInfo", Map.of("name", "Dougle"))));
        validate(BrowserProvider.NOTION, rpc("tools/list", Map.of()));
        BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST", URI.create("https://mcp.notion.com/mcp"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}", false, "synthetic", "session", null);
    }

    @Test void formOrRegistrationLabelsCannotHideBusinessWrites() {
        for (String body : List.of("grant_type=delete&resource=https://mcp.notion.com/mcp", "grant_type=refresh_token&client_id=fixture&refresh_token=fixture&resource=https://mcp.notion.com/mcp&operation=delete")) {
            assertThatThrownBy(() -> BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST",
                    URI.create("https://mcp.notion.com/token"), body, true, null, null, null)).isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> BrowserPluginHttp.validateReadOrAuthentication(BrowserProvider.NOTION, "POST",
                URI.create("https://mcp.notion.com/register"), "{\"client_name\":\"Dougle\",\"method\":\"delete\"}", false, null, null, null))
                .isInstanceOf(LibraryException.class);
    }

    @Test void serverReadOnlyHintCannotAuthorizeWriteToolAndExplicitFalseDeniesKnownReadTool() {
        for (boolean hint : List.of(false, true)) {
            var http = mock(BrowserPluginHttp.class);
            when(http.send(any(), anyString(), any(), anyString(), anyBoolean(), anyString(), nullable(String.class), nullable(String.class)))
                    .thenAnswer(call -> {
                        var sent = json.readTree((String) call.getArgument(3));
                        String method = sent.path("method").asText();
                        Object result = "initialize".equals(method) ? Map.of("protocolVersion", "2025-03-26")
                                : "tools/list".equals(method) ? Map.of("tools", List.of(Map.of("name", hint ? "notion-delete-page" : "notion-fetch",
                                        "annotations", Map.of("readOnlyHint", hint), "inputSchema", Map.of()))) : Map.of();
                        String id = call.getArgument(7);
                        return new BrowserPluginHttp.Reply(json.valueToTree(id == null ? Map.of() : Map.of("id", id, "result", result)), "session");
                    });
            assertThatThrownBy(() -> new PluginMcpSession(BrowserProvider.NOTION, http, "synthetic")).isInstanceOf(LibraryException.class);
        }
    }
}
