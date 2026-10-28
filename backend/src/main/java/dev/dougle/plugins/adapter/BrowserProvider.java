package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import java.util.Set;

public enum BrowserProvider {
    NOTION("Notion", "https://mcp.notion.com", "/mcp", Set.of("notion-fetch")),
    ATLASSIAN("Jira · Confluence", "https://mcp.atlassian.com", "/v2/mcp?tools=all",
            Set.of("getAccessibleAtlassianResources", "getJiraIssue", "getConfluenceContent", "getConfluencePage"));

    final String label;
    final String origin;
    final String path;
    final Set<String> readTools;
    BrowserProvider(String label, String origin, String path, Set<String> readTools) {
        this.label = label; this.origin = origin; this.path = path; this.readTools = readTools;
    }
    public static BrowserProvider parse(String value) {
        for (var provider : values()) if (provider.name().equalsIgnoreCase(value)) return provider;
        throw new LibraryException(404, "연결할 서비스를 찾을 수 없습니다.");
    }
    String resource() { return origin + (this == NOTION ? "/mcp" : "/v2/mcp"); }
    URI trusted(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getRawUserInfo() == null && uri.getFragment() == null && uri.getPort() == -1
                    && "https".equals(uri.getScheme()) && URI.create(origin).getHost().equals(uri.getHost())) return uri;
        } catch (IllegalArgumentException ignored) { }
        throw new LibraryException(502, "서비스의 인증 주소를 확인하지 못했습니다.");
    }
}
