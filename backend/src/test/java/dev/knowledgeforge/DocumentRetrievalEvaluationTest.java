package dev.knowledgeforge;

import dev.knowledgeforge.search.adapter.LocalSearchModel;
import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FORGE_DOCUMENT_EVALUATION", matches = ".+")
class DocumentRetrievalEvaluationTest {
    @TempDir Path directory;

    @Test void evaluatesUserApprovedDocumentWithoutExternalInference() throws Exception {
        Path evaluation = Path.of(System.getenv("FORGE_DOCUMENT_EVALUATION"));
        var json = new JsonMapper();
        var cases = json.readTree(Files.readString(evaluation.resolve("cases.json")));
        var results = json.createArrayNode();
        String content = Files.readString(evaluation.resolve("page.txt"));
        if (Files.exists(evaluation.resolve("mcp-response.json"))) {
            var response = json.readTree(Files.readString(evaluation.resolve("mcp-response.json")));
            try (var snapshot = new dev.knowledgeforge.plugins.adapter.BrowserPluginReader(null, null)
                    .documentSnapshot(response, response.path("structuredContent").path("url").asText())) {
                content = new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8);
                Files.writeString(evaluation.resolve("imported.txt"), content);
                Files.writeString(evaluation.resolve("imported-name.txt"), snapshot.files().getFirst().path());
            }
        }
        int answered = 0;
        int found = 0;
        try (var model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"));
             var index = new LuceneSearchIndex(directory.toString(), model)) {
            assertThat(model.ready()).isTrue();
            index.put(new SearchIndex.Document("document", "one", "가져온 문서", content,
                    "", "source", "", "connected-document.txt"));
            index.put(new SearchIndex.Document("unrelated", "one", "가상 운영 기록", "온실 센서의 수기 보정은 월 120건입니다.", "", "other", "", "operations.txt"));
            index.commit();
            for (var item : cases) {
                String question = item.path("question").asText();
                var hits = index.searchQuestion("one", question, List.of(), false);
                String original = content;
                String evidence = String.join("\n", hits.stream().limit(5).map(hit -> "document".equals(hit.entryId())
                        ? dev.knowledgeforge.search.domain.EvidenceWindow.around(original, hit.snippet(), hit.startLine(), hit.endLine(), 6000).text()
                        : hit.snippet()).toList());
                boolean expected = !item.path("evidence").isEmpty();
                boolean matched = !expected;
                for (var phrase : item.path("evidence")) if (evidence.contains(phrase.asText())) matched = true;
                if (expected && !matched) {
                    var method = LuceneSearchIndex.class.getDeclaredMethod("semantic", String.class, String.class, List.class, boolean.class);
                    method.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    var candidates = (List<SearchIndex.Hit>) method.invoke(index, "one", question, List.of(), false);
                    for (var candidate : candidates.stream().limit(5).toList()) {
                        System.out.println("DOCUMENT_CANDIDATE " + item.path("id").asText() + " vector=" + candidate.score()
                                + " relevance=" + model.relevance(question, candidate.path() + "\n" + candidate.snippet())
                                + " line=" + candidate.startLine());
                    }
                }
                if (expected) { answered++; if (matched) found++; }
                results.addObject().put("id", item.path("id").asText()).put("question", question)
                        .put("answerable", expected).put("evidenceMatched", matched)
                        .set("hits", json.valueToTree(hits));
                System.out.println("DOCUMENT_RETRIEVAL " + item.path("id").asText() + " matched=" + matched + " hits=" + hits.size());
            }
        }
        Files.writeString(evaluation.resolve("retrieval-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        System.out.println("DOCUMENT_RETRIEVAL_TOTAL " + found + "/" + answered);
        assertThat(found).as("Answer-bearing evidence recall").isEqualTo(answered);
    }
}
