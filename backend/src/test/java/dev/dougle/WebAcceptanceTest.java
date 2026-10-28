package dev.dougle;

import dev.dougle.ingestion.adapter.TikaJavaAnalyzer;
import dev.dougle.ingestion.domain.Analysis;
import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.sources.adapter.UrlCollector;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.domain.CollectedSnapshot;
import dev.dougle.sources.domain.HttpValidators;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

@EnabledIfSystemProperty(named = "forge.acceptance.collection", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebAcceptanceTest {
    @TempDir Path directory;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final TikaJavaAnalyzer analyzer = new TikaJavaAnalyzer();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/web-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 10; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(401 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_URL_COLLECTOR_ANALYZER_LEXICAL_INDEX_MOCKED_HTTP");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("Actual HTTP redirects/TLS/DNS not exercised", "UI registration and answer generation", "Semantic paraphrase evaluation"));
        long start = System.nanoTime();
        RemoteReader remote = mock(RemoteReader.class);
        URI uri = URI.create("https://example.org/acceptance/" + id + "/document.txt");
        HttpValidators validators = new HttpValidators(uri.toString(), "v1", null);
        String body = body(fixture);
        String media = fixture == 3 ? "text/plain" : "text/html";
        var collector = new UrlCollector(remote, null, null, null);
        try {
            when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, media, body.getBytes(StandardCharsets.UTF_8), false, validators));
            try (var snapshot = collector.collect(uri.toString())) {
                assertThat(snapshot.files()).hasSize(1);
                Analysis analysis = analyze(snapshot);
                result.put("analysisStatus", analysis.status());
                result.put("analysisError", analysis.error());
                result.put("extractedCharacters", analysis.text().length());
                result.put("fixtureHash", dev.dougle.sources.adapter.CollectionLimits.hash(body.getBytes(StandardCharsets.UTF_8)));
                if (fixture >= 5 && fixture <= 7) {
                    assertThat(analysis.status()).as("JS/login/CAPTCHA shell must not be searchable business evidence").isNotEqualTo("READY");
                } else {
                    assertThat(analysis.status()).isEqualTo("READY");
                    assertThat(analysis.text()).contains("BLUE72");
                    if (fixture == 0) assertThat(analysis.text()).doesNotContain("BLUE36", "SECRET_SCRIPT");
                    if (fixture == 1) assertThat(analysis.text()).contains("AMBER", "24", "GREEN", "12");
                    if (fixture == 2 || fixture == 3) assertThat(analysis.text()).contains("input < 3", "return input + 1;");
                    if (fixture == 8) {
                        result.put("unreadRange", "Only initial page is available; UI must not claim infinite-scroll completion");
                    }
                    if (action == 1) {
                        try (var index = new LuceneSearchIndex(directory.resolve(id).toString())) {
                            index.put(new SearchIndex.Document("page", "one", "page", analysis.text(), "", "web", "", "page"));
                            index.commit();
                            var hits = index.search("one", "BLUE72", List.of());
                            assertThat(hits).isNotEmpty();
                            assertThat(hits.getFirst().snippet()).contains("BLUE72");
                        }
                    }
                }
                if (action == 2) {
                    var next = new HttpValidators(uri.toString(), "v2", null);
                    when(remote.read(uri, validators)).thenReturn(new RemoteReader.Response(uri, media,
                            body.replace("BLUE72", "BLUE36").getBytes(StandardCharsets.UTF_8), false, next));
                    try (var updated = collector.collect(uri.toString(), snapshot)) {
                        assertThat(updated.validators().etag()).isEqualTo("v2");
                        if (body.contains("BLUE72")) assertThat(analyze(updated).text()).contains("BLUE36").doesNotContain("BLUE72");
                    }
                    verify(remote).read(uri, validators);
                } else if (action == 3) {
                    when(remote.read(uri, validators)).thenReturn(new RemoteReader.Response(uri, media, new byte[0], true, validators));
                    try (var unchanged = collector.collect(uri.toString(), snapshot)) {
                        assertThat(unchanged.revision()).isEqualTo(snapshot.revision());
                        assertThat(analyze(unchanged).text()).isEqualTo(analysis.text());
                    }
                    verify(remote).read(uri, validators);
                }
                verify(remote).read(uri);
                verifyNoMoreInteractions(remote);
                result.put("transportCalls", mockingDetails(remote).getInvocations().size());
                result.put("status", "PARTIAL");
                result.put("checkStatus", "PASS");
            }
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.getMessage());
            throw failure;
        } finally {
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("WEB_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }

    private Analysis analyze(CollectedSnapshot snapshot) throws Exception {
        var file = snapshot.files().getFirst();
        try (var input = file.open()) { return analyzer.analyzeStream(file.path(), input); }
    }

    private String body(int fixture) {
        return switch (fixture) {
            case 0 -> "<html><head><title>Synthetic policy</title></head><body><nav>BLUE36</nav><main><h1>Policy</h1><p>BLUE72</p></main><footer>BLUE36</footer><script>SECRET_SCRIPT()</script></body></html>";
            case 1 -> "<html><body><main>BLUE72<table><tr><th>Item</th><th>Quantity</th></tr><tr><td>AMBER</td><td>24</td></tr><tr><td>GREEN</td><td>12</td></tr></table></main></body></html>";
            case 2 -> "<html><body><main>BLUE72<pre>if (input &lt; 3) {\n    return input + 1;\n}</pre></main></body></html>";
            case 3 -> "BLUE72\nif (input < 3) {\n    return input + 1;\n}";
            case 4 -> "<html><head><title></title></head><body><main>BLUE72</main></body></html>";
            case 5 -> "<html><body><div id='app'></div><script src='/app.js'></script></body></html>";
            case 6 -> "<html><body><main><h1>Sign in</h1><form><label>Email</label><input><label>Password</label><input type='password'><button>Log in</button></form></main></body></html>";
            case 7 -> "<html><body><main><h1>Verify you are human</h1><p>Complete the CAPTCHA to continue</p></main></body></html>";
            case 8 -> "<html><body><main>BLUE72</main><button data-next='/next'>Load more</button><script>loadNextPage()</script></body></html>";
            case 9 -> "<html><body><main>" + "Synthetic filler observation. ".repeat(4000) + "BLUE72</main></body></html>";
            default -> throw new IllegalArgumentException();
        };
    }
}
