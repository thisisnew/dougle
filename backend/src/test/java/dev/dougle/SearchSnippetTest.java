package dev.dougle;

import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SearchSnippetTest {
    @Test
    void repeatedPrefixesDoNotHideTheSpecificNumberInTheSnippet() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            String text = "PAGE993 synthetic filler observation\n".repeat(13) + "PAGE1000 final policy\n";
            put(index, "one", "workspace", "", "pages", text, "");
            assertThat(index.search("workspace", "PAGE1000", List.of()).getFirst().snippet()).contains("PAGE1000");
        }
    }

    @Test
    void preservesSemanticFormatAfterAsynchronousModelRestart() throws Exception {
        var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
        var model = new dev.dougle.search.application.TextEmbedding() {
            public boolean ready() { return ready.get(); }
            public float[] encode(String text, boolean question) { return new float[] { 1, 0 }; }
        };
        try (var index = new LuceneSearchIndex(data.toString(), model)) {
            put(index, "one", "workspace", "", "문서", "기존 내용", "");
        }
        ready.set(false);
        try (var index = new LuceneSearchIndex(data.toString(), model)) {
            ready.set(true);
            assertThat(index.initialized()).isTrue();
            put(index, "two", "workspace", "", "문서", "새 내용", "");
            assertThat(index.initialized()).isTrue();
        }
    }

    @Test
    void ranksPassagesBeforeRemovingDuplicateDocuments() throws Exception {
        var model = new dev.dougle.search.application.TextEmbedding() {
            public boolean ready() { return true; }
            public float[] encode(String text, boolean question) { return new float[] { 1, 0 }; }
            public float relevance(String question, String text) { return text.contains("실제 단서") ? 0.9f : 0.01f; }
        };
        try (var index = new LuceneSearchIndex(data.toString(), model)) {
            put(index, "one", "workspace", "", "가상 문서", "앞부분 설명\n".repeat(180) + "실제 단서: 정답은 나중 문단\n", "");
            var hits = index.searchQuestion("workspace", "나중 문단의 단서", List.of(), false);
            assertThat(hits).hasSize(1);
            assertThat(hits.getFirst().snippet()).contains("실제 단서");
            assertThat(hits.getFirst().startLine()).isGreaterThan(1);
        }
    }

    @Test
    void interruptedRebuildIsNotMistakenForCompleteIndexAfterRestart() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "one", "workspace", "", "note", "synthetic observations", "");
            assertThat(index.initialized()).isTrue();
            index.reset();
            index.put(new SearchIndex.Document("partial", "workspace", "partial", "partial observations", "", "", "", "partial"));
        }
        try (var reopened = new LuceneSearchIndex(data.toString())) {
            assertThat(reopened.initialized()).isFalse();
            reopened.reset();
            put(reopened, "one", "workspace", "", "note", "synthetic observations", "");
            assertThat(reopened.initialized()).isTrue();
        }
    }

    @Test
    void naturalQuestionFindsEvidenceWithoutRequiringEveryQuestionWord() throws Exception {
        try (var index = new LuceneSearchIndex(data.toString())) {
            put(index, "answer", "workspace", "", "가상 운영 기록", "입하보정 수기 처리: 월평균 12건", "");
            put(index, "unrelated", "workspace", "", "가상 관측", "망원경 렌즈 점검", "");
            put(index, "private", "other", "", "가상 운영 기록", "입하보정 수기 처리: 월평균 99건", "");
            assertThat(index.searchQuestion("workspace", "입하보정이 월평균 몇건정도 수기로 보정하나?", List.of(), false))
                    .extracting(SearchIndex.Hit::entryId).containsExactly("answer");
            assertThat(index.searchQuestion("workspace", "!!!", List.of(), false)).isEmpty();
            assertThat(index.searchQuestion("workspace", "unmatchedword", List.of(), false)).isEmpty();
            assertThat(index.searchQuestion("workspace", "입하보정", List.of("excluded"), false)).isEmpty();
        }
    }

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
