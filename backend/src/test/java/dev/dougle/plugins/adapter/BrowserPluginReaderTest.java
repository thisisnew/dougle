package dev.dougle.plugins.adapter;

import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserPluginReaderTest {
    final JsonMapper json = new JsonMapper();
    final List<JsonNode> calls = new ArrayList<>();

    @Test void notionJsonTextPreservesRealParagraphsAndDocumentTitle() {
        var document = Map.of("title", "가상 프로세스", "text", "# 가상 프로세스\n\n첫 번째 설명\n\n두 번째 설명");
        var result = json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(document)))));
        try (var snapshot = reader().documentSnapshot(result, "https://fixture.notion.site/0123456789abcdef0123456789abcdef")) {
            assertThat(snapshot.files().getFirst().path()).isEqualTo("가상 프로세스.txt");
            assertThat(new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("첫 번째 설명\n\n두 번째 설명").doesNotContain("\\n", "\"text\":");
        }
    }

    @Test void connectedDocumentTitlesCannotEscapeTheSourceFolder() {
        var result = json.valueToTree(Map.of("structuredContent", Map.of("title", "../../가상/문서", "text", "가상 내용")));
        try (var snapshot = reader().documentSnapshot(result, "https://fixture.notion.site/0123456789abcdef0123456789abcdef")) {
            assertThat(snapshot.files().getFirst().path()).doesNotContain("/", "\\");
        }
    }
    BrowserPluginReader reader() {
        var connections = mock(BrowserPluginConnections.class);
        when(connections.accessToken(any())).thenReturn("synthetic");
        var http = mock(BrowserPluginHttp.class);
        when(http.send(any(), anyString(), any(), nullable(String.class), anyBoolean(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    var payload = json.readTree((String) invocation.getArgument(3));
                    Object result;
                    switch (payload.path("method").asText()) {
                        case "initialize" -> result = Map.of("protocolVersion", "2025-03-26");
                        case "tools/list" -> result = Map.of("tools", List.of(tool("notion-fetch", "id"), tool("getAccessibleAtlassianResources"),
                                tool("getJiraIssue", "cloudId", "issueIdOrKey"), tool("getConfluenceContent", "cloudId", "contentId")));
                        case "tools/call" -> {
                            calls.add(payload.path("params"));
                            result = payload.path("params").path("name").asText().equals("getAccessibleAtlassianResources")
                                    ? Map.of("content", List.of(Map.of("type", "text", "text", "[{\"url\":\"https://fixture.atlassian.net\",\"id\":\"fixture-cloud\"}]")))
                                    : Map.of("content", List.of(Map.of("type", "text", "text", "가상 프로젝트 자료 본문")));
                        }
                        default -> result = Map.of();
                    }
                    String id = invocation.getArgument(7);
                    return new BrowserPluginHttp.Reply(json.valueToTree(id == null ? Map.of() : Map.of("id", id, "result", result)), "session");
                });
        return new BrowserPluginReader(connections, http);
    }
    Map<String, Object> tool(String name, String... required) {
        var properties = new LinkedHashMap<String, Object>();
        for (String field : required) properties.put(field, Map.of("type", "string"));
        return Map.of("name", name, "inputSchema", Map.of("properties", properties, "required", List.of(required)));
    }
    @Test void notionUsesOnlyFetchForTheSelectedPageAndPreservesSource() {
        String link = "https://www.notion.so/0123456789abcdef0123456789abcdef";
        var snapshot = reader().collect(BrowserProvider.NOTION, link);
        assertThat(new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8)).contains(link, "가상 프로젝트 자료 본문");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().path("name").asText()).isEqualTo("notion-fetch");
        assertThat(calls.getFirst().path("arguments").path("id").asText()).isEqualTo("0123456789abcdef0123456789abcdef");
    }
    @Test void jiraAndConfluenceUseTheAuthorizedCloudAndReadOnlyToolSchemas() {
        var reader = reader();
        reader.collect(BrowserProvider.ATLASSIAN, "https://fixture.atlassian.net/browse/DEMO-12");
        reader.collect(BrowserProvider.ATLASSIAN, "https://fixture.atlassian.net/wiki/spaces/DEMO/pages/123/Fixture");
        assertThat(calls.get(1).path("arguments").path("cloudId").asText()).isEqualTo("fixture-cloud");
        assertThat(calls.get(1).path("arguments").path("issueIdOrKey").asText()).isEqualTo("DEMO-12");
        assertThat(calls.get(3).path("name").asText()).isEqualTo("getConfluenceContent");
        assertThat(calls.get(3).path("arguments").path("contentId").asText()).isEqualTo("123");
    }
    @Test void unknownSitesAndSpoofedHostsNeverFetchContent() {
        var reader = reader();
        assertThatThrownBy(() -> reader.collect(BrowserProvider.ATLASSIAN, "https://other.atlassian.net/browse/DEMO-12")).hasMessageContaining("접근 권한");
        assertThat(calls).hasSize(1);
        assertThatThrownBy(() -> reader.collect(BrowserProvider.ATLASSIAN, "https://fixture.atlassian.net.evil.example/browse/DEMO-12")).hasMessageContaining("자료 링크");
        assertThat(calls).hasSize(1);
    }
}
