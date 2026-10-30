package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchIndex.Hit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NamedCodeFocusTest {
    private final Hit strategy = hit("patterns/Strategy.java", "interface Strategy {}", 0.01f);
    private final Hit state = hit("patterns/State.java", "class NightState {}", 0.01f);

    @Test void explicitNameRejectsOnlyWeakUnrelatedCode() {
        var targets = NamedCodeFocus.targets("Strategy 예제", List.of(strategy, state));
        assertThat(targets).containsExactly("Strategy");
        assertThat(NamedCodeFocus.allows(strategy, targets)).isTrue();
        assertThat(NamedCodeFocus.allows(state, targets)).isFalse();
        assertThat(NamedCodeFocus.allows(hit("Player.java", "Strategy selected;", 0.01f), targets)).isTrue();
    }

    @Test void comparisonsRetainEachNamedImplementation() {
        var targets = NamedCodeFocus.targets("Strategy와 State를 비교해줘", List.of(strategy, state));
        assertThat(targets).containsExactlyInAnyOrder("Strategy", "State");
        assertThat(NamedCodeFocus.allows(strategy, targets)).isTrue();
        assertThat(NamedCodeFocus.allows(state, targets)).isTrue();
    }

    @Test void unnamedParaphraseKeepsSemanticCandidates() {
        var targets = NamedCodeFocus.targets("외부에서 선택 알고리즘을 전달하는 구현은?", List.of(strategy, state));
        assertThat(targets).isEmpty();
        assertThat(NamedCodeFocus.allows(state, targets)).isTrue();
    }

    @Test void unknownNamesAndIncidentalEnglishAreNotConstraints() {
        assertThat(NamedCodeFocus.targets("Where is MissingType used?", List.of(strategy, state))).isEmpty();
        assertThat(NamedCodeFocus.targets("StrategyFactory 예제", List.of(strategy, state))).isEmpty();
    }

    @Test void strongRelatedEvidenceAndProseRemainEligible() {
        assertThat(NamedCodeFocus.allows(hit("Hand.java", "class Hand {}", 0.5f), Set.of("Strategy"))).isTrue();
        assertThat(NamedCodeFocus.allows(hit("guide.md", "손 선택 예제 설명", 0.01f), Set.of("Strategy"))).isTrue();
    }

    @Test void declarationsAreRecognizedWithoutMatchingFileName() {
        var declaration = hit("Example.java", "record RenderPolicy(int mode) {}", 0.01f);
        assertThat(NamedCodeFocus.targets("RenderPolicy 예제", List.of(declaration))).containsExactly("RenderPolicy");
        assertThat(NamedCodeFocus.allows(hit("Other.java", "class RenderPolicyFactory {}", 0.01f), Set.of("RenderPolicy"))).isFalse();
    }

    private static Hit hit(String path, String text, float score) {
        return new Hit(path, path, "synthetic", "", path, text, score, 1, 1, true,
                "ORIGINAL", null, null, List.of(), "CURRENT", "synthetic");
    }
}
