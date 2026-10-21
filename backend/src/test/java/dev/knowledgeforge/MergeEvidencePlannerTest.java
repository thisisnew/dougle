package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.MergeEvidencePlanner;
import dev.knowledgeforge.knowledge.domain.Knowledge.Evidence;
import dev.knowledgeforge.library.application.LibraryException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MergeEvidencePlannerTest {
    private final MergeEvidencePlanner planner = new MergeEvidencePlanner();

    private Evidence evidence(String snapshot, int start, int end) {
        return new Evidence(snapshot, "entry", null, null, null, null, null, "fixture", "fixture", "hash",
                start, end, "same quote", "CURRENT", "now");
    }

    @Test
    void swapsOrdinalsInOnePassWithoutCascadingReplacements() {
        var first = evidence("first", 1, 1);
        var second = evidence("second", 1, 1);
        var plan = planner.plan(List.of(second, first), List.of(first, second), "first [E1], second [E2], first again [E1]");
        assertThat(plan.mapping()).containsExactly(2, 1);
        assertThat(plan.generatedMarkdown()).isEqualTo("first [E2], second [E1], first again [E2]");
        assertThat(plan.evidence()).containsExactly(second, first);
    }

    @Test
    void equalQuotesDoNotMergeDifferentSnapshotsOrLineRanges() {
        var current = evidence("same", 1, 1);
        var newRange = evidence("same", 1, 2);
        var newSnapshot = evidence("other", 1, 1);
        var plan = planner.plan(List.of(current), List.of(newRange, newSnapshot, current), "[E1] [E2] [E3]");
        assertThat(plan.mapping()).containsExactly(2, 3, 1);
        assertThat(plan.evidence()).containsExactly(current, newRange, newSnapshot);
    }

    @Test
    void rejectsMalformedAndOutOfBoundsReferences() {
        var evidence = List.of(evidence("same", 1, 1));
        for (String markdown : List.of("[E0]", "[E01]", "[E2]", "[E-1]", "[Ex]", "[E999999999999999999999]")) {
            assertThatThrownBy(() -> planner.plan(evidence, evidence, markdown)).isInstanceOf(LibraryException.class);
        }
    }
}
