package dev.dougle;

import dev.dougle.search.adapter.LocalSearchModel;
import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.TextEmbedding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.search", matches = "true")
class NamedCodeRetrievalTest {
    @TempDir Path directory;

    @Test void namedStrategyExampleDoesNotIncludeUnrelatedStateImplementation() throws Exception {
        try (var model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"))) {
            assertThat(model.ready()).isTrue();
            var diagnostic = new TextEmbedding() {
                public boolean ready() { return model.ready(); }
                public float[] encode(String text, boolean question) { return model.encode(text, question); }
                public float relevance(String question, String text) {
                    float score = model.relevance(question, text);
                    System.out.println("NAMED_CODE_SCORE " + question + " score=" + score + " text=" + text.replace('\n', ' '));
                    return score;
                }
            };
            try (var index = new LuceneSearchIndex(directory.toString(), diagnostic)) {
                index.put(document("patterns/Strategy.java", "package patterns;\ninterface Strategy { int choose(); }\nclass FixedStrategy implements Strategy { public int choose() { return 7; } }\nclass Player { private final Strategy strategy; Player(Strategy strategy) { this.strategy = strategy; } int play() { return strategy.choose(); } }"));
                index.put(document("distractors/State.java", "package distractors;\nclass NightState { void doClock(int hour) { if (hour >= 9 && hour < 17) System.out.println(\"DayState\"); } }"));
                index.put(document("policy.txt", "Synthetic badge policy. BLUE expires 72 hours after issuance. AMBER expires 24 hours after issuance. Neither badge renews automatically."));
                index.commit();
                var hits = index.searchQuestion("one", "Strategy 예제", List.of(), false);
                assertThat(hits).extracting(SearchIndex.Hit::path).contains("patterns/Strategy.java").doesNotContain("distractors/State.java");
            }
        }
    }

    private SearchIndex.Document document(String path, String text) {
        return new SearchIndex.Document(path, "one", Path.of(path).getFileName().toString(), text, "", "synthetic", "", path);
    }
}
