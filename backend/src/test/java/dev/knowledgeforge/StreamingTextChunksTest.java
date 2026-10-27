package dev.knowledgeforge;

import dev.knowledgeforge.search.domain.StreamingTextChunks;
import dev.knowledgeforge.search.domain.TextChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StreamingTextChunksTest {
    @Test
    void storageBoundariesDoNotChangeSearchWindowsOffsetsOrLines() {
        String original = "가상 관측 🌱\n".repeat(1800) + "final evidence";
        var parts = new ArrayList<String>();
        for (int offset = 0; offset < original.length(); offset += 617) {
            parts.add(original.substring(offset, Math.min(offset + 617, original.length())));
        }
        var streamed = StreamSupport.stream(new StreamingTextChunks(parts, 768).spliterator(), false).toList();
        var inline = StreamSupport.stream(new TextChunks(original, 768).spliterator(), false).toList();
        assertThat(streamed).isEqualTo(inline);
    }

    @Test
    void longLinesAndSurrogatesSurviveWithoutMaterializingTheDocument() {
        String text = "🌱".repeat(5000);
        var chunks = new StreamingTextChunks(List.of(text.substring(0, 601), text.substring(601)), 513);
        var result = new StringBuilder();
        for (var chunk : chunks) {
            assertThat(chunk.text()).hasSizeLessThanOrEqualTo(513);
            assertThat(chunk.startLine()).isEqualTo(1);
            assertThat(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1))).isFalse();
            result.append(chunk.text());
        }
        assertThat(result.toString()).isEqualTo(text);
    }
}
