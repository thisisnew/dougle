package dev.knowledgeforge;

import dev.knowledgeforge.search.domain.EvidenceWindow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvidenceWindowTest {
    @Test void includesShortParentDocumentWithoutInventingContent() {
        var window = EvidenceWindow.around("정의\n설명\n단서\n", "단서\n", 3, 3, 12000);
        assertThat(window.text()).isEqualTo("정의\n설명\n단서\n");
        assertThat(window.startLine()).isEqualTo(1);
        assertThat(window.endLine()).isEqualTo(3);
    }

    @Test void expandsLongDocumentsWithinBudgetAndKeepsExactLines() {
        String before = "앞 문장입니다.\n".repeat(500);
        String original = before + "필요한 단서\n" + "뒤 문장입니다.\n".repeat(500);
        var window = EvidenceWindow.around(original, "필요한 단서\n", 501, 501, 1200);
        assertThat(window.text()).contains("필요한 단서").hasSizeLessThanOrEqualTo(1200);
        assertThat(String.join("\n", original.lines().skip(window.startLine() - 1).limit(window.endLine() - window.startLine() + 1).toList()) + "\n").isEqualTo(window.text());
    }

    @Test void doesNotConfuseRepeatedPassagesOrSplitUnicode() {
        String original = "같은 문장\n" + "🙂".repeat(4000) + "\n같은 문장\n마지막 설명";
        var window = EvidenceWindow.around(original, "같은 문장\n", 3, 3, 1200);
        assertThat(window.text()).endsWith("마지막 설명");
        assertThat(Character.isLowSurrogate(window.text().charAt(0))).isFalse();
    }
}
