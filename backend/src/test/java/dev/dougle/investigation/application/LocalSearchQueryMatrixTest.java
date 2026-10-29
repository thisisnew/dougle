package dev.dougle.investigation.application;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class LocalSearchQueryMatrixTest {
    record QueryText(String unit, String expected, boolean blank) {}

    static Stream<Arguments> queries() {
        return Stream.of(new QueryText("a", "a", false), new QueryText("한", "한", false),
                new QueryText("検", "検", false), new QueryText("ع", "ع", false),
                new QueryText("🌱", "🌱", false), new QueryText("e\u0301", "e\u0301", false),
                new QueryText("\"?*", "\"?*", false), new QueryText("../", "../", false),
                new QueryText("\u00a0", "\u00a0", false), new QueryText(" ", "", true),
                new QueryText("\t", "", true), new QueryText("\u2003", "", true))
                .flatMap(query -> IntStream.of(0, 1, 2, 3, 4, 7, 15, 31, 63, 64, 65, 84,
                        85, 86, 126, 127, 128, 129, 253, 254, 255, 256, 257, 512)
                        .mapToObj(repetitions -> Arguments.of(query, repetitions)));
    }

    @ParameterizedTest(name = "QUERY-{index}: query={0} repetitions={1}")
    @MethodSource("queries")
    void rejectsBlankAndOversizeQueriesWithoutRewritingSearchSyntax(QueryText query, int repetitions) {
        String core = query.unit().repeat(repetitions);
        String input = "\t" + core + "\n";
        if (query.blank() || repetitions == 0 || input.length() > 256) {
            assertThatThrownBy(() -> new LocalSearchQuery(input)).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(new LocalSearchQuery(input).question()).isEqualTo(query.expected().repeat(repetitions));
        }
    }
}
