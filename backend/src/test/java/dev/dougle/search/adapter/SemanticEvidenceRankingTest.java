package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.TextEmbedding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SemanticEvidenceRankingTest {
    @TempDir Path directory;

    @ParameterizedTest(name = "EVIDENCE-RANK-{index}: path={0}")
    @ValueSource(strings = {"operations.pdf", "archive/2026/report.txt", "자료/운영.md", "REPORT.PDF",
            "opaque-0137.html", "emoji-🌱.txt", "a b/document.docx", "unrelated-title.csv"})
    void filenamesCannotSuppressRelevantBodyEvidenceOrPromoteUnrelatedBodies(String path) throws Exception {
        String body = "A synthetic passage answering the question.";
        String unrelated = "An unrelated synthetic passage.";
        var scored = new ArrayList<String>();
        TextEmbedding embeddings = new TextEmbedding() {
            public boolean ready() { return true; }
            public float[] encode(String text, boolean question) { return new float[] {1, 0}; }
            public float relevance(String question, String text) {
                scored.add(text);
                return text.equals(body) ? 0.04f : 0.001f;
            }
        };
        try (var index = new LuceneSearchIndex(directory.toString(), embeddings)) {
            index.put(new SearchIndex.Document("answer", "one", path, body, "", "source", "", path));
            index.put(new SearchIndex.Document("noise", "one", "answer.pdf", unrelated, "", "source", "", "answer.pdf"));
            index.commit();
            var hits = index.searchQuestion("one", "paraphrased request", List.of(), false);
            assertThat(hits).extracting(SearchIndex.Hit::entryId).containsExactly("answer");
            assertThat(hits.getFirst().snippet()).isEqualTo(body);
            assertThat(hits.getFirst().score()).isEqualTo(0.04f);
            assertThat(scored).containsExactlyInAnyOrder(body, unrelated);
        }
    }
}
