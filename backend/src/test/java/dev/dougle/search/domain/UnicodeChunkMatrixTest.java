package dev.dougle.search.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class UnicodeChunkMatrixTest {
    static Stream<Arguments> windows() {
        return Stream.of("plainCamelCase", "한글 검색 자료", "日本語の検索", "العربية بحث",
                "cafe\u0301 naïve", "🌱🧑‍💻🚀", "first\nsecond\n", "section\r\n\r\nbody\n")
                .flatMap(unit -> IntStream.of(256, 257, 258, 300, 511, 512, 513, 767, 768, 1024)
                        .boxed().flatMap(limit -> IntStream.of(1, 2, 3, 7, 31, 127, 255, 256, 257, 511, 768, 1024)
                                .mapToObj(partSize -> Arguments.of(unit, limit, partSize))));
    }

    @ParameterizedTest(name = "CHUNK-{index}: unicode={0} limit={1} storage={2}")
    @MethodSource("windows")
    void storageSplitsPreserveExactSourceCoverageAndUnicode(String unit, int limit, int partSize) {
        String original = unit.repeat(2200 / unit.length() + 1) + "\nEND 🌱";
        var parts = new ArrayList<String>();
        parts.add("");
        for (int offset = 0; offset < original.length(); offset += partSize) {
            parts.add(original.substring(offset, Math.min(original.length(), offset + partSize)));
        }
        parts.add("");
        List<TextChunks.Chunk> inline = StreamSupport.stream(new TextChunks(original, limit).spliterator(), false).toList();
        List<TextChunks.Chunk> streamed = StreamSupport.stream(new StreamingTextChunks(parts, limit).spliterator(), false).toList();
        assertChunks(original, limit, inline);
        assertChunks(original, limit, streamed);
        assertThat(streamed).isEqualTo(inline);
        var iterator = new StreamingTextChunks(parts, limit).iterator();
        for (var expected : streamed) {
            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isEqualTo(expected);
        }
        assertThat(iterator.hasNext()).isFalse();
        assertThatThrownBy(iterator::next).isInstanceOf(java.util.NoSuchElementException.class);
    }

    private static void assertChunks(String original, int limit, List<TextChunks.Chunk> chunks) {
        int covered = 0;
        int previousOffset = -1;
        var reconstructed = new StringBuilder();
        for (var chunk : chunks) {
            assertThat(chunk.offset()).isGreaterThan(previousOffset).isLessThanOrEqualTo(covered);
            assertThat(chunk.text()).isNotEmpty().hasSizeLessThanOrEqualTo(limit);
            int end = chunk.offset() + chunk.text().length();
            assertThat(chunk.text()).isEqualTo(original.substring(chunk.offset(), end));
            assertThat(chunk.startLine()).isEqualTo(1 + (int) original.substring(0, chunk.offset()).chars()
                    .filter(character -> character == '\n').count());
            assertThat(Character.isLowSurrogate(chunk.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1))).isFalse();
            assertThat(end).isGreaterThan(covered);
            reconstructed.append(chunk.text().substring(covered - chunk.offset()));
            covered = end;
            previousOffset = chunk.offset();
        }
        assertThat(covered).isEqualTo(original.length());
        assertThat(reconstructed.toString()).isEqualTo(original);
    }
}
