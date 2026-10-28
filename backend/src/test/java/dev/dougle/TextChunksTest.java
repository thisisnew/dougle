package dev.dougle;

import dev.dougle.search.domain.TextChunks;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TextChunksTest {
    @Test
    void largeUnbrokenTextUsesBoundedWindowsAndKeepsEveryCharacter() {
        String text = "x".repeat(16 * 1024 * 1024);
        int length = 0;
        for (var chunk : new TextChunks(text, 768)) length += chunk.text().length();
        assertThat(length).isEqualTo(text.length());
        for (var chunk : new TextChunks("a".repeat(767) + "\n\n" + "b".repeat(800), 768)) {
            assertThat(chunk.text()).hasSizeLessThanOrEqualTo(768);
        }
    }

    @Test
    void prefersKnownDeclarationStartWhenSplittingCode() {
        String first = "class Synthetic {\n" + "  int value = 0;\n".repeat(30);
        String second = "  void collect() {\n" + "    observe();\n".repeat(50) + "  }\n}";
        String text = first + second;
        int methodLine = 1 + (int) first.chars().filter(value -> value == '\n').count();
        var chunks = StreamSupport.stream(new TextChunks(text, 600, java.util.List.of(1, methodLine)).spliterator(), false).toList();
        assertThat(chunks.getFirst().text()).isEqualTo(first);
        assertThat(chunks.get(1).text()).contains("void collect()");
    }
    @Test
    void splitsWithRealOffsetsAndLineNumbersAndKeepsFinalEvidence() {
        String text = "가상 관측 🌱\n".repeat(2000) + "final evidence";
        var chunks = StreamSupport.stream(new TextChunks(text, 1200).spliterator(), false).toList();
        assertThat(chunks.size()).isGreaterThan(10);
        for (var chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(1200);
            assertThat(text.substring(chunk.offset(), chunk.offset() + chunk.text().length())).isEqualTo(chunk.text());
            assertThat(chunk.startLine()).isEqualTo(1 + (int) text.substring(0, chunk.offset()).chars().filter(value -> value == '\n').count());
            assertThat(Character.isLowSurrogate(chunk.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1))).isFalse();
        }
        assertThat(chunks.getLast().text()).endsWith("final evidence");
    }

    @Test
    void emptyDocumentAndSingleLongLineTerminate() {
        assertThat(StreamSupport.stream(new TextChunks("", 512).spliterator(), false).toList()).hasSize(1);
        var chunks = StreamSupport.stream(new TextChunks("🌱".repeat(3000), 513).spliterator(), false).toList();
        assertThat(chunks.stream().map(TextChunks.Chunk::text).collect(java.util.stream.Collectors.joining()))
                .isEqualTo("🌱".repeat(3000));
    }
}
