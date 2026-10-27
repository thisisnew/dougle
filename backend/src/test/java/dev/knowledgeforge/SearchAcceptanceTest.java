package dev.knowledgeforge;

import dev.knowledgeforge.search.adapter.LocalSearchModel;
import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.application.TextEmbedding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.search", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchAcceptanceTest {
    @TempDir Path directory;
    private LocalSearchModel model;
    private TextEmbedding cached;
    private final JsonMapper mapper = new JsonMapper();
    private final List<Map<String, Object>> results = new ArrayList<>();

    @BeforeAll void prepare() {
        model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"));
        assertThat(model.ready()).as("Real bundled model must be available; no keyword fallback").isTrue();
        cached = new TextEmbedding() {
            private final Map<String, float[]> vectors = new HashMap<>();
            private final Map<String, Float> relevance = new HashMap<>();
            public boolean ready() { return model.ready(); }
            public float[] encode(String text, boolean question) {
                return vectors.computeIfAbsent(question + "\u0000" + text, key -> model.encode(text, question));
            }
            public float relevance(String question, String text) {
                return relevance.computeIfAbsent(question + "\u0000" + text, key -> model.relevance(question, text));
            }
        };
    }

    @AfterAll void close() throws Exception {
        try {
            Path report = Path.of(System.getProperty("forge.acceptance.output", "target/search-acceptance-results.json"));
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        } finally { if (model != null) model.close(); }
    }

    @TestFactory Stream<DynamicTest> cases() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readString(Path.of("../docs/fixtures/search-acceptance.json")));
        assertThat(fixtures.size()).isEqualTo(150);
        var tests = new ArrayList<DynamicTest>();
        for (JsonNode fixture : fixtures) {
            tests.add(DynamicTest.dynamicTest(fixture.path("id").asText(), () -> execute(fixture)));
        }
        return tests.stream();
    }

    private void execute(JsonNode fixture) throws Exception {
        String id = fixture.path("id").asText();
        int action = fixture.path("action").asInt();
        long start = System.nanoTime();
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("fixtureHash", fixture.path("fixtureHash").asText());
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_BUNDLED_MODEL_AND_LUCENE_SYNTHETIC_CORPUS");
        result.put("externalRequests", 0);
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("UI/answer evidence assembly not executed", fixture.path("remaining").asText("none")));
        try (var index = new LuceneSearchIndex(directory.resolve(id).toString(), cached)) {
            List<SearchIndex.Document> documents = new ArrayList<>();
            for (JsonNode item : fixture.path("documents")) {
                documents.add(new SearchIndex.Document(item.path("id").asText(), "one",
                        Path.of(item.path("path").asText()).getFileName().toString(), item.path("text").asText(),
                        "", "synthetic", "", item.path("path").asText()));
            }
            if (action == 1) {
                for (int number = 0; number < 50; number++) {
                    documents.add(new SearchIndex.Document("noise-" + number, "one", "garden-" + number + ".txt",
                            "가상 온실 " + number + "번 구역에서는 화분에 물을 주고 꽃의 생장과 잎의 색을 관찰합니다.",
                            "", "noise", "", "garden-" + number + ".txt"));
                }
            }
            if (action == 2) Collections.reverse(documents);
            for (var document : documents) index.put(document);
            index.commit();
            if (action == 3) {
                index.reset();
                for (var document : documents) index.put(document);
                index.commit();
            }
            if (fixture.path("remaining").asText("").equals("DELETE_AND_IMMUTABLE_HISTORY_REQUIRED")) {
                for (JsonNode document : fixture.path("documents")) index.remove(document.path("id").asText());
                index.commit();
            }
            if (action == 4) {
                index.put(new SearchIndex.Document("private", "other", "private.txt", fixture.path("question").asText()
                        + "\nPRIVATE_ONLY_MARKER", "", "private", "", "private.txt"));
                index.commit();
            }
            var hits = index.searchQuestion("one", fixture.path("question").asText(), List.of(), false);
            result.put("paths", hits.stream().map(SearchIndex.Hit::path).toList());
            result.put("snippets", hits.stream().map(SearchIndex.Hit::snippet).toList());
            result.put("scores", hits.stream().map(SearchIndex.Hit::score).toList());
            assertThat(hits).extracting(SearchIndex.Hit::entryId).doesNotContain("private");
            assertThat(hits).allSatisfy(hit -> assertThat(hit.snippet()).doesNotContain("PRIVATE_ONLY_MARKER"));
            String remaining = fixture.path("remaining").asText("");
            if (fixture.path("paths").isEmpty()) {
                if (!remaining.contains("ANSWER_GATE")) assertThat(hits).as("No answer-bearing evidence expected").isEmpty();
                else result.put("answerGateObservation", "Retrieval is not an answer; abstention/freshness requires AI/validator evaluation");
            } else {
                var top = hits.stream().limit(5).toList();
                for (JsonNode path : fixture.path("paths")) {
                    assertThat(top).extracting(SearchIndex.Hit::path).contains(path.asText());
                }
                String snippets = String.join("\n", top.stream().map(SearchIndex.Hit::snippet).toList());
                for (JsonNode fragment : fixture.path("fragments")) assertThat(snippets).contains(fragment.asText());
            }
            result.put("status", "PARTIAL");
            result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            throw failure;
        } finally {
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("SEARCH_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }
}
