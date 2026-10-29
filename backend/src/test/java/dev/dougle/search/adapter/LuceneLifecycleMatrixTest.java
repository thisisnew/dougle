package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class LuceneLifecycleMatrixTest {
    @TempDir Path directory;

    static Stream<Arguments> documents() {
        return Stream.of("telescope", "관측", "検索", "العربية", "café", "naïve", "über", "данные")
                .flatMap(term -> IntStream.of(0, 100, 390, 400, 800).boxed()
                        .flatMap(lines -> IntStream.of(0, 1, 2, 7, 257, 2401)
                                .mapToObj(storage -> Arguments.of(term, lines, storage))));
    }

    @ParameterizedTest(name = "LUCENE-{index}: term={0} precedingLines={1} storage={2}")
    @MethodSource("documents")
    void indexedTextSurvivesQueryIsolationReplacementReopenAndDeletion(String term, int lines, int storage) throws Exception {
        String text = "intro\n".repeat(lines) + "quartzsignal " + term + " 🌱 evidence\n" + "tail\n".repeat(30);
        String path = "자료/" + term + ".txt";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        try (var index = new LuceneSearchIndex(directory.toString())) {
            index.put(document("target", "one", "research", path, text, storage));
            index.put(document("foreign", "two", "research", path, text, storage));
            index.put(document("excluded", "one", "excluded", path, text, storage));
            index.put(document("distractor", "one", "research", "unrelated.txt", "ordinary background", storage));
            index.commit();
            assertThat(index.initialized()).isTrue();
            var hits = index.search("one", "quartzsignal", List.of("research"));
            assertThat(hits).extracting(SearchIndex.Hit::entryId).containsExactly("target");
            var hit = hits.getFirst();
            assertThat(hit.path()).isEqualTo(path);
            assertThat(hit.sourceId()).isEqualTo("synthetic-source");
            assertThat(hit.bodyMatch()).isTrue();
            assertThat(hit.snippet()).contains("quartzsignal", term);
            assertThat(hit.startLine()).isLessThanOrEqualTo(lines + 1);
            assertThat(hit.endLine()).isGreaterThanOrEqualTo(lines + 1);
            assertThat(hit.textHash()).isEqualTo(hash);
            assertThat(index.search("one", term, List.of("research")))
                    .extracting(SearchIndex.Hit::entryId).containsExactly("target");
            assertThat(index.searchQuestion("one", "quartzsignal", List.of("research"), false))
                    .extracting(SearchIndex.Hit::entryId).containsExactly("target");
            assertThat(index.search("missing", "quartzsignal", List.of())).isEmpty();
            assertThat(index.search("one", "quartzsignal", List.of("missing"))).isEmpty();
            assertThat(index.search("one", "unfindablemarker", List.of())).isEmpty();
            index.put(document("target", "one", "research", path, "replacementmarker 🌱 " + term, storage));
            index.commit();
            assertThat(index.search("one", "quartzsignal", List.of("research"))).isEmpty();
            assertThat(index.search("one", "replacementmarker", List.of("research")))
                    .extracting(SearchIndex.Hit::entryId).containsExactly("target");
        }
        try (var reopened = new LuceneSearchIndex(directory.toString())) {
            assertThat(reopened.initialized()).isTrue();
            var hits = reopened.search("one", "replacementmarker", List.of("research"));
            assertThat(hits).extracting(SearchIndex.Hit::entryId).containsExactly("target");
            assertThat(hits.getFirst().startLine()).isEqualTo(1);
            assertThat(hits.getFirst().snippet()).isEqualTo("replacementmarker 🌱 " + term);
            reopened.remove("target");
            reopened.commit();
            assertThat(reopened.search("one", "replacementmarker", List.of())).isEmpty();
            assertThat(reopened.search("two", "quartzsignal", List.of("research")))
                    .extracting(SearchIndex.Hit::entryId).containsExactly("foreign");
        }
    }

    private static SearchIndex.Document document(String id, String workspace, String domain,
                                                  String path, String text, int storage) {
        var parts = new ArrayList<String>();
        if (storage > 0) {
            for (int offset = 0; offset < text.length(); offset += storage) {
                parts.add(text.substring(offset, Math.min(text.length(), offset + storage)));
            }
        }
        return new SearchIndex.Document(id, workspace, "synthetic.txt", storage == 0 ? text : "", "",
                "synthetic-source", domain, path, "ORIGINAL", null, null, List.of(domain), List.of(), parts);
    }
}
