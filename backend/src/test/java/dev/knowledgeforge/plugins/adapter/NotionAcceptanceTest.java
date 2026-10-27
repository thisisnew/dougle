package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "forge.acceptance.notion", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotionAcceptanceTest {
    private final JsonMapper json = new JsonMapper();
    private final List<Map<String, Object>> results = new ArrayList<>();
    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/notion-acceptance-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) for (int action = 0; action < 5; action++) {
            int selectedFixture = fixture;
            int selectedAction = action;
            String id = "T%04d".formatted(501 + fixture * 5 + action);
            tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
        }
        return tests.stream();
    }
    private void execute(String id, int fixture, int action) throws Exception {
        var record = new LinkedHashMap<String, Object>();
        record.put("id", id); record.put("startedAt", Instant.now().toString());
        record.put("scope", "REAL_NOTION_MCP_READER_ANALYZER_MOCKED_AUTH_AND_HTTP");
        record.put("fullAcceptancePassed", false);
        record.put("remaining", List.of("Actual approved Notion tenant, nested block completeness and inline database pagination", "UI registration/search/reranking and actual AI", "App process restart; reader recreation only"));
        long start = System.nanoTime();
        var connections = mock(BrowserPluginConnections.class);
        var http = mock(BrowserPluginHttp.class);
        var calls = new ArrayList<String>();
        var allowed = new AtomicBoolean(fixture != 4);
        String page = "0123456789abcdef0123456789abcdef";
        String url = fixture == 1 ? "https://app.notion.com/p/" + page : "https://synthetic.notion.site/" + page;
        String text = switch (fixture) {
            case 2 -> "# Synthetic\n<details><summary>Toggle</summary>BLUE72 nested paragraph</details>\nAMBER24 end";
            case 3 -> "# Synthetic\n| Badge | Hours |\n| --- | --- |\n| BLUE | 72 |\n| AMBER | 24 |\nBLUE72\nAMBER24";
            default -> "# Synthetic\nBLUE72 beginning\n" + "synthetic background. ".repeat(200) + "MIDDLE48\nAMBER24 end";
        };
        when(connections.accessToken(BrowserProvider.NOTION)).thenReturn("synthetic-token");
        when(http.send(any(), anyString(), any(), nullable(String.class), anyBoolean(), nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String body = invocation.getArgument(3);
                    var payload = json.readTree(body);
                    String method = payload.path("method").asText();
                    calls.add(method);
                    Object result;
                    if (method.equals("initialize")) result = Map.of("protocolVersion", "2025-03-26");
                    else if (method.equals("tools/list")) result = Map.of("tools", List.of(Map.of("name", "notion-fetch",
                            "annotations", Map.of("readOnlyHint", true), "inputSchema", Map.of("properties", Map.of("id", Map.of("type", "string")), "required", List.of("id")))));
                    else if (method.equals("tools/call")) {
                        assertThat(payload.path("params").path("name").asText()).isEqualTo("notion-fetch");
                        assertThat(payload.path("params").path("arguments").path("id").asText()).isEqualTo(page);
                        if (!allowed.get()) throw new LibraryException(403, "Notion 페이지 접근 권한이 없습니다.");
                        result = Map.of("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(Map.of("title", "Synthetic page", "text", text)))));
                    } else { assertThat(method).isEqualTo("notifications/initialized"); result = Map.of(); }
                    String requestId = invocation.getArgument(7);
                    return new BrowserPluginHttp.Reply(json.valueToTree(requestId == null ? Map.of() : Map.of("id", requestId, "result", result)), "synthetic-session");
                });
        try {
            var reader = new BrowserPluginReader(connections, http);
            if (fixture == 1) {
                when(connections.accessToken(BrowserProvider.NOTION)).thenThrow(new LibraryException(409, "Notion 연결이 필요합니다."));
                assertThatThrownBy(() -> reader.collect(BrowserProvider.NOTION, url)).isInstanceOf(LibraryException.class);
                verifyNoInteractions(http);
                doReturn("synthetic-token").when(connections).accessToken(BrowserProvider.NOTION);
            }
            int repetitions = action == 2 ? 2 : 1;
            for (int attempt = 0; attempt < repetitions; attempt++) {
                if (fixture == 4) {
                    assertThatThrownBy(() -> reader.collect(BrowserProvider.NOTION, url)).isInstanceOf(LibraryException.class)
                            .hasMessageContaining("접근 권한").hasMessageNotContaining("synthetic-token");
                    record.put("deniedPage", true);
                    if (action != 3) continue;
                    allowed.set(true);
                }
                var selectedReader = action == 3 ? new BrowserPluginReader(connections, http) : reader;
                try (var snapshot = selectedReader.collect(BrowserProvider.NOTION, url)) {
                    assertThat(snapshot.files()).hasSize(1);
                    var file = snapshot.files().getFirst();
                    String content = new String(file.content(), StandardCharsets.UTF_8);
                    assertThat(file.path()).isEqualTo("Synthetic page.txt");
                    assertThat(content).contains(url, "BLUE72", "AMBER24").doesNotContain("synthetic-token");
                    var analysis = new TikaJavaAnalyzer().analyze(file.path(), file.content());
                    assertThat(analysis.status()).isEqualTo("READY");
                    if (fixture == 3) assertThat(analysis.text()).contains("| BLUE | 72 |", "| AMBER | 24 |");
                    if (fixture == 2) assertThat(analysis.text()).contains("nested paragraph");
                    record.put("contentHash", file.hash()); record.put("extractedCharacters", analysis.text().length());
                }
            }
            record.put("status", "PARTIAL"); record.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            record.put("status", "FAIL"); record.put("checkStatus", "FAIL"); record.put("failure", failure.toString()); throw failure;
        } finally {
            record.put("methods", calls); record.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000); results.add(record);
        }
    }
}
