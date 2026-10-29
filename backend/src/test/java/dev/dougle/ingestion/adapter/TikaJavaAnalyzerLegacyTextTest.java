package dev.dougle.ingestion.adapter;

import dev.dougle.ingestion.application.ProcessingProgress;
import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TikaJavaAnalyzerLegacyTextTest {
    private static final String TEXT = "입하 정답은 BLUE72입니다. 중간 AMBER24 마지막 GREEN12";
    private static final Charset MS949 = Charset.forName("MS949");
    @TempDir Path directory;

    static Stream<Arguments> legacyStreams() {
        return Stream.of("입하.txt", "LEGACY.TXT").flatMap(name -> Stream.of(1, 3, 4093)
                .map(fragment -> Arguments.of(name, fragment)));
    }

    @ParameterizedTest(name = "LEGACY-SEARCH-{index}: name={0} fragmentBytes={1}")
    @MethodSource("legacyStreams")
    void legacyKoreanTextRetainsExactBodyAndSearchMatches(String name, int fragment) throws Exception {
        byte[] original = TEXT.getBytes(MS949);
        Path file = Files.write(directory.resolve(name), original);
        var modified = Files.getLastModifiedTime(file);
        try (var input = new FilterInputStream(Files.newInputStream(file)) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, fragment));
            }
        }; var analysis = new TikaJavaAnalyzer().analyzeStream(name, input);
             var index = new LuceneSearchIndex(directory.resolve("index").toString())) {
            assertThat(analysis.status()).isEqualTo("READY");
            assertThat(analysis.error()).isNull();
            assertThat(analysis.text()).isEqualTo(TEXT).doesNotContain("\ufffd");
            assertThat(analysis.symbols()).isEmpty();
            index.put(new SearchIndex.Document("target", "synthetic", name, analysis.text(), "",
                    "source", "", name));
            index.commit();
            for (String marker : List.of("입하", "BLUE72", "AMBER24", "GREEN12")) {
                assertThat(index.search("synthetic", marker, List.of())).extracting(hit -> hit.entryId())
                        .containsExactly("target");
            }
        }
        assertThat(Files.readAllBytes(file)).containsExactly(original);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    @ParameterizedTest(name = "LEGACY-CODE-{index}: name={0}")
    @ValueSource(strings = {"Sample.java", "build.gradle", "config.yaml", "config.json", "sample.py", "sample.xml"})
    void doesNotGuessLegacyEncodingForCodeOrConfiguration(String name) {
        byte[] content = TEXT.getBytes(MS949);
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer().analyze(name, content)) {
            assertThat(analysis.status()).isEqualTo("ERROR");
            assertThat(analysis.error()).contains("인코딩");
            assertThat(analysis.text()).isEmpty();
        }
        assertThat(content).containsExactly(original);
    }

    @ParameterizedTest(name = "LEGACY-INVALID-{index}: corruption={0}")
    @ValueSource(strings = {"INVALID_TRAILER", "NUL", "UTF8_BOM"})
    void detectedLegacyEncodingStillRejectsCorruptOrMisdeclaredText(String corruption) {
        byte[] legacy = TEXT.getBytes(MS949);
        byte[] content;
        if (corruption.equals("UTF8_BOM")) {
            content = new byte[legacy.length + 3];
            content[0] = (byte) 0xef;
            content[1] = (byte) 0xbb;
            content[2] = (byte) 0xbf;
            System.arraycopy(legacy, 0, content, 3, legacy.length);
        } else {
            content = java.util.Arrays.copyOf(legacy, legacy.length + 1);
            content[legacy.length] = corruption.equals("NUL") ? 0 : (byte) 0xff;
        }
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer().analyze("입하.txt", content)) {
            assertThat(analysis.status()).isEqualTo("ERROR");
            assertThat(analysis.error()).contains("인코딩");
            assertThat(analysis.text()).isEmpty();
            assertThat(analysis.parts()).isEmpty();
        }
        assertThat(content).containsExactly(original);
    }

    @ParameterizedTest(name = "LEGACY-LIMIT-{index}: budgetDelta={0}")
    @ValueSource(ints = {-1, 0})
    void legacyTextStillHonorsUtf8ExtractionBudget(int delta) {
        byte[] content = TEXT.getBytes(MS949);
        byte[] original = content.clone();
        int budget = TEXT.getBytes(StandardCharsets.UTF_8).length + delta;
        try (var analysis = new TikaJavaAnalyzer(budget).analyze("입하.txt", content)) {
            assertThat(analysis.status()).isEqualTo(delta < 0 ? "ERROR" : "READY");
            assertThat(analysis.text()).isEqualTo(delta < 0 ? "" : TEXT);
            if (delta < 0) assertThat(analysis.error()).contains("한도");
        }
        assertThat(content).containsExactly(original);
    }

    @ParameterizedTest(name = "LEGACY-CANCEL-{index}: phase={0}")
    @ValueSource(strings = {"SAMPLING", "EXTRACTION"})
    void legacyDetectionAndExtractionRemainCancellable(String phase) throws Exception {
        byte[] content = (TEXT + "\n").repeat(4000).getBytes(MS949);
        byte[] original = content.clone();
        var analyzer = new TikaJavaAnalyzer();
        try (var observation = ProcessingProgress.observe(step -> {
            if (step.stage().equals("EXTRACTING_CHARS")) throw new CancellationException("synthetic cancellation");
        }); var input = new FilterInputStream(new ByteArrayInputStream(content)) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = super.read(buffer, offset, Math.min(length, 3));
                if (phase.equals("SAMPLING")) Thread.currentThread().interrupt();
                return count;
            }
        }) {
            try {
                assertThatThrownBy(() -> analyzer.analyzeStream("입하.txt", input)).isInstanceOf(CancellationException.class);
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(content).containsExactly(original);
        try (var recovery = analyzer.analyze("입하.txt", TEXT.getBytes(MS949))) {
            assertThat(recovery.status()).isEqualTo("READY");
            assertThat(recovery.text()).isEqualTo(TEXT);
        }
    }

    @Test
    void doesNotGuessWhenLegacyTextOccursOnlyBeyondDetectionWindow() {
        byte[] content = ("x".repeat(64 * 1024) + TEXT).getBytes(MS949);
        byte[] original = content.clone();
        try (var analysis = new TikaJavaAnalyzer().analyze("입하.txt", content)) {
            assertThat(analysis.status()).isEqualTo("ERROR");
            assertThat(analysis.text()).isEmpty();
            assertThat(analysis.error()).contains("인코딩");
        }
        assertThat(content).containsExactly(original);
    }
}
