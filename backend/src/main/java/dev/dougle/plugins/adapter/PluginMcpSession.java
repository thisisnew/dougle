package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PluginMcpSession {
    private final BrowserProvider provider;
    private final BrowserPluginHttp http;
    private final String token;
    private final JsonMapper json = new JsonMapper();
    private String session;
    private final Map<String, JsonNode> tools = new HashMap<>();

    PluginMcpSession(BrowserProvider provider, BrowserPluginHttp http, String token) {
        this.provider = provider; this.http = http; this.token = token;
        JsonNode initialized = rpc("initialize", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "Dougle", "version", "0.1.20")), false);
        if (!"2025-03-26".equals(initialized.path("protocolVersion").asText()))
            throw new LibraryException(502, "서비스 연결 규격이 변경되었습니다. 앱 업데이트를 확인해 주세요.");
        rpc("notifications/initialized", Map.of(), true);
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            var result = rpc("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), false);
            if (!result.path("tools").isArray()) throw new LibraryException(502, "서비스의 읽기 기능을 확인하지 못했습니다.");
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText();
                var readOnlyHint = tool.path("annotations").path("readOnlyHint");
                if (provider.readTools.contains(name) && !(readOnlyHint.isBoolean() && !readOnlyHint.asBoolean())) tools.put(name, tool.path("inputSchema"));
            }
            cursor = result.path("nextCursor").asText("");
            if (cursor.isEmpty()) break;
            if (page == 19) throw new LibraryException(502, "서비스의 기능 목록이 너무 큽니다.");
        }
        if (tools.isEmpty()) throw new LibraryException(403, "자료 읽기 권한이 없습니다. 서비스 또는 회사 관리자에게 권한을 확인해 주세요.");
    }

    boolean has(String tool) { return tools.containsKey(tool); }
    JsonNode schema(String tool) {
        if (!provider.readTools.contains(tool) || !has(tool)) throw new LibraryException(403, "이 자료의 읽기 기능이 허용되지 않았습니다.");
        return tools.get(tool);
    }
    JsonNode call(String tool, Map<String, Object> arguments) {
        JsonNode schema = schema(tool);
        validateReadArguments(provider, tool, json.valueToTree(arguments));
        for (JsonNode required : schema.path("required")) if (!arguments.containsKey(required.asText()))
            throw new LibraryException(502, "자료 읽기 형식이 변경되었습니다. 앱 업데이트를 확인해 주세요.");
        JsonNode result = rpc("tools/call", Map.of("name", tool, "arguments", arguments), false);
        if (result.path("isError").asBoolean()) throw new LibraryException(403, "자료를 읽지 못했습니다. 링크와 접근 권한을 확인해 주세요.");
        return result;
    }

    static void validateReadArguments(BrowserProvider provider, String tool, JsonNode arguments) {
        if (!provider.readTools.contains(tool) || !arguments.isObject()) throw BrowserPluginHttp.readOnlyDenied();
        Set<String> identifiers = switch (tool) {
            case "notion-fetch" -> Set.of("id");
            case "getAccessibleAtlassianResources" -> Set.of();
            case "getJiraIssue" -> Set.of("issueIdOrKey", "issueKey", "issueId");
            case "getConfluenceContent", "getConfluencePage" -> Set.of("contentId", "pageId", "id");
            default -> throw BrowserPluginHttp.readOnlyDenied();
        };
        if (identifiers.isEmpty()) {
            if (!arguments.isEmpty()) throw BrowserPluginHttp.readOnlyDenied();
            return;
        }
        long selected = identifiers.stream().filter(arguments::has).count();
        boolean cloud = provider == BrowserProvider.ATLASSIAN;
        if (selected != 1 || arguments.size() != (cloud ? 2 : 1)) throw BrowserPluginHttp.readOnlyDenied();
        if (cloud && (!arguments.path("cloudId").isTextual() || !arguments.path("cloudId").asText().matches("[A-Za-z0-9_-]{1,128}"))) {
            throw BrowserPluginHttp.readOnlyDenied();
        }
        for (String name : identifiers) if (arguments.has(name)
                && (!arguments.path(name).isTextual() || !arguments.path(name).asText().matches("[A-Za-z0-9_-]{1,128}"))) {
            throw BrowserPluginHttp.readOnlyDenied();
        }
    }
    private JsonNode rpc(String method, Map<String, ?> parameters, boolean notification) {
        String id = UUID.randomUUID().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("jsonrpc", "2.0"); payload.put("method", method); payload.put("params", parameters);
        if (!notification) payload.put("id", id);
        var reply = http.send(provider, "POST", URI.create(provider.origin + provider.path), json.writeValueAsString(payload), false, token, session, notification ? null : id);
        session = reply.session();
        if (notification) return reply.body();
        if (!id.equals(reply.body().path("id").asText()) || reply.body().has("error") || !reply.body().has("result"))
            throw new LibraryException(502, "서비스 요청을 처리하지 못했습니다. 권한과 앱 버전을 확인해 주세요.");
        return reply.body().path("result");
    }
    static String text(JsonNode result) {
        var content = new StringBuilder();
        for (JsonNode part : result.path("content")) if ("text".equals(part.path("type").asText())) content.append(part.path("text").asText()).append('\n');
        if (content.isEmpty() && result.has("structuredContent")) content.append(result.path("structuredContent").toString());
        if (content.isEmpty()) throw new LibraryException(422, "읽을 수 있는 텍스트가 없습니다.");
        if (content.length() > 2_000_000) throw new LibraryException(413, "자료가 너무 큽니다. 작은 단위로 추가해 주세요.");
        return content.toString();
    }
}
