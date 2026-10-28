package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.adapter.CollectionLimits;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BrowserPluginReader {
    private final BrowserPluginConnections connections;
    private final BrowserPluginHttp http;
    private final JsonMapper json = new JsonMapper();
    public BrowserPluginReader(BrowserPluginConnections connections, BrowserPluginHttp http) { this.connections = connections; this.http = http; }
    public boolean connected(BrowserProvider provider) { return connections.connected(provider); }
    public CollectedSnapshot collect(BrowserProvider provider, String location) {
        URI uri = URI.create(location);
        var detected = dev.dougle.plugins.domain.PluginProvider.forWebLink(uri);
        if (detected.isEmpty() || !detected.get().name().equals(provider.name()) || uri.getUserInfo() != null || uri.getPort() != -1)
            throw new LibraryException(400, "서비스의 자료 링크를 등록해 주세요.");
        String token = connections.accessToken(provider);
        try { return collectAuthorized(provider, location, uri, token); }
        catch (LibraryException exception) {
            if (exception.status() == 401) connections.invalidate(provider, token);
            throw exception;
        }
    }
    private CollectedSnapshot collectAuthorized(BrowserProvider provider, String location, URI uri, String token) {
        var session = new PluginMcpSession(provider, http, token);
        JsonNode result;
        if (provider == BrowserProvider.NOTION) {
            var matcher = Pattern.compile("([a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})/?$").matcher(uri.getPath());
            if (!matcher.find()) throw new LibraryException(400, "Notion 페이지 링크를 등록해 주세요.");
            result = session.call("notion-fetch", Map.of("id", matcher.group(1)));
        } else {
            JsonNode resources = payload(session.call("getAccessibleAtlassianResources", Map.of()));
            if (!resources.isArray()) resources = resources.path("resources");
            String cloud = null;
            for (JsonNode resource : resources) {
                if (("https://" + uri.getHost()).equals(resource.path("url").asText().replaceFirst("/$", ""))) cloud = resource.path("id").asText();
            }
            if (cloud == null || cloud.isBlank()) throw new LibraryException(403, "이 회사 사이트의 접근 권한이 없습니다. 해당 사이트로 다시 연결해 주세요.");
            var issue = Pattern.compile("/browse/([A-Za-z][A-Za-z0-9_]*-[0-9]+)(?:/)?$").matcher(uri.getPath());
            var page = Pattern.compile("/pages/([0-9]+)(?:/|$)").matcher(uri.getPath());
            if (issue.find()) {
                var arguments = new LinkedHashMap<String, Object>(); arguments.put("cloudId", cloud);
                putId(session, "getJiraIssue", arguments, issue.group(1), "issueIdOrKey", "issueKey", "issueId");
                result = session.call("getJiraIssue", arguments);
            } else {
                String pageId = page.find() ? page.group(1) : null;
                if (pageId == null && uri.getQuery() != null) pageId = BrowserPluginConnections.query(uri.getRawQuery()).get("pageId");
                if (pageId == null || !pageId.matches("[0-9]+")) throw new LibraryException(400, "Jira 이슈 또는 Confluence 페이지의 전체 링크를 등록해 주세요.");
                String tool = session.has("getConfluenceContent") ? "getConfluenceContent" : "getConfluencePage";
                var arguments = new LinkedHashMap<String, Object>(); arguments.put("cloudId", cloud);
                putId(session, tool, arguments, pageId, "contentId", "pageId", "id");
                result = session.call(tool, arguments);
            }
        }
        return documentSnapshot(result, location);
    }

    public CollectedSnapshot documentSnapshot(JsonNode result, String location) {
        String content = PluginMcpSession.text(result);
        JsonNode document = result.path("structuredContent");
        if (!document.isObject()) {
            try { document = json.readTree(content); }
            catch (Exception ignored) { document = json.createObjectNode(); }
        }
        String title = document.path("title").asText("");
        if (document.path("text").isTextual()) content = document.path("text").asText();
        else if (document.path("content").isTextual()) content = document.path("content").asText();
        var body = Pattern.compile("(?s)<content>\\s*(.*?)\\s*</content>").matcher(content);
        if (body.find()) content = body.group(1);
        content = content.replaceAll("\\[([^\\]\\n]+)]\\(https?://[^\\s]+\\)", "$1")
                .replaceAll("</?(?:b|strong|em|i|p|quote)>", "");
        if (title.isBlank()) {
            var heading = Pattern.compile("(?m)^#{1,2}\\s+(.+)$").matcher(content);
            if (heading.find()) title = heading.group(1).strip();
        }
        if (title.isBlank()) {
            String path = URI.create(location).getPath();
            title = path.substring(path.lastIndexOf('/') + 1).replaceFirst("-?[a-fA-F0-9]{32}$", "");
        }
        title = title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", " ").strip();
        if (title.isBlank() || title.equals(".") || title.equals("..")) title = "연결 문서";
        title = title.substring(0, title.offsetByCodePoints(0, Math.min(80, title.codePointCount(0, title.length()))));
        String text = title + "\n출처: " + location + "\n\n" + content;
        return CollectionLimits.snapshot(List.of(new CollectedSnapshot.File(title + ".txt", text.getBytes(StandardCharsets.UTF_8))));
    }
    private void putId(PluginMcpSession session, String tool, Map<String, Object> arguments, String value, String... names) {
        JsonNode properties = session.schema(tool).path("properties");
        for (String name : names) if (properties.has(name)) { arguments.put(name, value); return; }
        throw new LibraryException(502, "자료 읽기 형식이 변경되었습니다. 앱 업데이트를 확인해 주세요.");
    }
    private JsonNode payload(JsonNode result) {
        if (result.has("structuredContent")) return result.path("structuredContent");
        try { return json.readTree(PluginMcpSession.text(result)); }
        catch (Exception exception) { throw new LibraryException(502, "회사 사이트 목록을 읽지 못했습니다."); }
    }
}
