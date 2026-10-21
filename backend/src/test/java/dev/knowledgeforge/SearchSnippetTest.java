package dev.knowledgeforge;

import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SearchSnippetTest {
    @TempDir Path data;

    private void put(LuceneSearchIndex index, String id, String workspace, String domain,
                     String name, String text, String symbols) {
        index.put(new SearchIndex.Document(id, workspace, name, text, symbols, "source", domain, name));
        index.commit();
    }

    @Test
    void findsSeparatedTermsDeepInBodyAndReportsExtractedTextLines() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            String text = "header\r\n".repeat(100) + "alpha unrelated beta\n" + "tail\n".repeat(100);
            put(index, "one", "workspace", "research", "note", text, "");
            var hit = index.search("workspace", "ALPHA beta", List.of()).getFirst();
            assertThat(hit.bodyMatch()).isTrue();
            assertThat(hit.snippet()).contains("alpha unrelated beta").hasSizeLessThanOrEqualTo(320);
            assertThat(hit.startLine()).isGreaterThan(1).isLessThanOrEqualTo(101);
            assertThat(hit.endLine()).isGreaterThanOrEqualTo(101);
            int offset = text.indexOf(hit.snippet());
            assertThat(hit.startLine()).isEqualTo(1 + (int) text.substring(0, offset).chars().filter(value -> value == '\n').count());
        }
    }

    @Test
    void koreanInflectionUsesIndexedOffsetsRatherThanLiteralQuestion() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "", "note", "서문\n".repeat(200) + "관측을 기록했습니다.", "");
            var hit = index.search("workspace", "관측", List.of()).getFirst();
            assertThat(hit.bodyMatch()).isTrue();
            assertThat(hit.snippet()).contains("관측을");
            assertThat(hit.endLine()).isEqualTo(201);
        }
    }

    @Test
    void metadataOnlyMatchIsNotPresentedAsBodyEvidence() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "", "telescope", "unrelated body\n", "");
            put(index, "two", "workspace", "", "empty", "", "telescope");
            var hits = index.search("workspace", "telescope", List.of());
            assertThat(hits).hasSize(2).allMatch(hit -> !hit.bodyMatch());
            var empty = hits.stream().filter(hit -> hit.entryId().equals("two")).findFirst().orElseThrow();
            assertThat(empty.startLine()).isZero();
            assertThat(empty.endLine()).isZero();
            var body = hits.stream().filter(hit -> hit.entryId().equals("one")).findFirst().orElseThrow();
            assertThat(body.startLine()).isEqualTo(1);
            assertThat(body.endLine()).isEqualTo(1);
        }
    }

    @Test
    void retainsWorkspaceAndMultipleDomainIsolationAndNoResults() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "research", "note", "telescope", "");
            put(index, "two", "workspace", "literature", "note", "telescope", "");
            put(index, "three", "other", "research", "note", "telescope", "");
            assertThat(index.search("workspace", "telescope", List.of("research", "literature")))
                    .extracting(SearchIndex.Hit::entryId).containsExactlyInAnyOrder("one", "two");
            assertThat(index.search("workspace", "telescope", List.of("research"))).hasSize(1);
            assertThat(index.search("workspace", "absent", List.of())).isEmpty();
            assertThat(index.search("workspace", "!!!", List.of())).isEmpty();
        }
    }

    @Test
    void updatesAndDeletesReplaceStoredOffsets() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "", "note", "intro\n".repeat(100) + "telescope", "");
            put(index, "one", "workspace", "", "note", "telescope", "");
            var hit = index.search("workspace", "telescope", List.of()).getFirst();
            assertThat(hit.startLine()).isEqualTo(1);
            assertThat(hit.endLine()).isEqualTo(1);
            index.remove("one");
            index.commit();
            assertThat(index.search("workspace", "telescope", List.of())).isEmpty();
        }
    }

    @Test
    void storedOffsetsSurviveReopeningIndex() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "", "note", "intro\n".repeat(100) + "telescope", "");
        }
        try (var index = new LuceneSearchIndex(data.toString())) {
            var hit = index.search("workspace", "telescope", List.of()).getFirst();
            assertThat(hit.bodyMatch()).isTrue();
            assertThat(hit.endLine()).isEqualTo(101);
        }
    }
}
