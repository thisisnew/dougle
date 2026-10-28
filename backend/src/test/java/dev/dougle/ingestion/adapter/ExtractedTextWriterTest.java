package dev.dougle.ingestion.adapter;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExtractedTextWriterTest {
    @Test
    void inlineBudgetCountsUtf8BytesAndAllowsTheExactLimit() throws Exception {
        try (var writer = new ExtractedTextWriter(4)) {
            writer.write("🌱");
            assertThat(writer.finish()).containsExactly("🌱");
        }
        try (var writer = new ExtractedTextWriter(3)) {
            writer.write("🌱");
            assertThatThrownBy(writer::finish).isInstanceOf(ExtractedTextWriter.SizeLimitException.class);
        }
    }

    @Test
    void spoolStopsBeforeWritingBeyondItsByteBudget() throws Exception {
        try (var writer = new ExtractedTextWriter(100_000)) {
            assertThatThrownBy(() -> writer.write("x".repeat(2_100_000)))
                    .isInstanceOf(ExtractedTextWriter.SizeLimitException.class);
        }
    }

    @Test
    void analyzerRetainsCompleteTextBelowBudgetAndRejectsWrappedSpoolOverflow() {
        var analyzer = new TikaJavaAnalyzer(100_000);
        String text = "first evidence\n" + "가상 자료\n".repeat(1000) + "final evidence";
        try (var analysis = analyzer.analyze("synthetic.txt", text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            assertThat(analysis.status()).isEqualTo("READY");
            assertThat(analysis.text()).isEqualTo(text);
        }
        try (var analysis = analyzer.analyze("expanded.txt", "x".repeat(2_100_000).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            assertThat(analysis.status()).isEqualTo("ERROR");
            assertThat(analysis.error()).contains("저장 용량 한도", "100000", "문서를 나누");
            assertThat(analysis.text()).isEmpty();
            assertThat(analysis.parts()).isEmpty();
        }
    }

    @Test
    void cancellationStillTakesPrecedenceOverAnExtractionBudget() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new TikaJavaAnalyzer(1).analyze("Synthetic.java", new byte[] {'a', 'b'}))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
        } finally { Thread.interrupted(); }
    }

    @Test
    void inlineTextKeepsNormalizationAndCompleteLegacyApi() throws Exception {
        try (var writer = new ExtractedTextWriter()) {
            writer.write("  e\u0301\r");
            writer.write("\nsecond\rthird\u0000  ");
            assertThat(writer.finish()).containsExactly("é\nsecond\nthird");
        }
    }

    @Test
    void spoolKeepsAllCharactersAndClosesDeterministically() throws Exception {
        java.util.List<String> parts;
        String block = "🌱\r\n가상 e\u0301\u0000".repeat(7000);
        try (var writer = new ExtractedTextWriter()) {
            for (int count = 0; count < 40; count++) writer.write(block);
            parts = writer.finish();
        }
        assertThat(parts.size()).isGreaterThan(30);
        long length = 0;
        for (String part : parts) {
            assertThat(part.length()).isLessThanOrEqualTo(ExtractedTextWriter.PART_SIZE);
            assertThat(part).doesNotContain("\r", "\u0000", "\ufffd");
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
            length += part.length();
        }
        assertThat(length).isEqualTo((long) java.text.Normalizer.normalize(
                block.replace("\r\n", "\n").replace("\u0000", ""), java.text.Normalizer.Form.NFC).length() * 40);
        ((AutoCloseable) parts).close();
        assertThatThrownBy(() -> parts.getFirst()).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void normalizesCombiningMarksHangulCrLfAndSurrogatesAcrossStorageBoundaries() throws Exception {
        String source = "x".repeat(65535) + "e\u0301\r\n\u1100\u1161\u11a8🌱";
        String expected = java.text.Normalizer.normalize(source.replace("\r\n", "\n").repeat(33),
                java.text.Normalizer.Form.NFC);
        java.util.List<String> parts;
        try (var writer = new ExtractedTextWriter()) {
            for (int repetition = 0; repetition < 33; repetition++) {
                for (int start = 0; start < source.length(); start += 127) {
                    writer.write(source.substring(start, Math.min(start + 127, source.length())));
                }
            }
            parts = writer.finish();
        }
        try (var cleanup = (AutoCloseable) parts) {
            assertThat(String.join("", parts)).isEqualTo(expected);
        }
    }

    @Test
    void cancellationStopsSpoolTraversalImmediately() throws Exception {
        java.util.List<String> parts;
        try (var writer = new ExtractedTextWriter()) {
            writer.write("x".repeat(2_100_000));
            parts = writer.finish();
        }
        try (var cleanup = (AutoCloseable) parts) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> parts.getFirst()).isInstanceOf(java.util.concurrent.CancellationException.class);
            } finally { Thread.interrupted(); }
        }
    }
}
