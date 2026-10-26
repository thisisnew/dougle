package dev.knowledgeforge.security.application;

import dev.knowledgeforge.investigation.application.InvestigationPlanPreview;
import dev.knowledgeforge.knowledge.application.CuratorSettings;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.*;
import dev.knowledgeforge.search.domain.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialPreviewTest {
    final EvidenceBundleService bundles = mock(EvidenceBundleService.class);
    final EvidenceBundleValidator validator = mock(EvidenceBundleValidator.class);
    final CuratorSettings settings = mock(CuratorSettings.class);
    final AnswerContextProvider contexts = mock(AnswerContextProvider.class);
    final EvidenceAnswerPreview answers = new EvidenceAnswerPreview(bundles, validator, settings, contexts);
    final InvestigationPlanPreview plans = new InvestigationPlanPreview(answers, bundles, settings);

    EvidenceBundle bundle(String question, List<EvidenceBundle.Item> items, EvidenceBundle.ChatContext context) {
        var bundle = new EvidenceBundle("bundle", "workspace", "now", "immutable-digest",
                new EvidenceBundle.Payload(question, List.of(), false, items.isEmpty() ? "NO_EVIDENCE" : "READY",
                        0, 12000, items, List.of(), context));
        when(bundles.get("workspace", "bundle")).thenReturn(bundle);
        when(validator.check("workspace", "bundle")).thenReturn(new BundleValidation("bundle", "immutable-digest", "now", "READY", true, List.of()));
        return bundle;
    }

    @Test void answerAndNoEvidencePlanRejectLegacySecretQuestionsWithoutChangingBundle() {
        var original = bundle("password=Synthet1c-Only-Value-7823", List.of(), null);
        assertThatThrownBy(() -> answers.prepare("workspace", "bundle", "CODEX", "model")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> plans.prepare("workspace", "bundle", "CODEX", "model")).isInstanceOf(LibraryException.class);
        assertThat(bundles.get("workspace", "bundle")).isSameAs(original);
        assertThat(original.digest()).isEqualTo("immutable-digest");
        assertThat(original.payload().question()).isEqualTo("password=Synthet1c-Only-Value-7823");
    }

    @Test void evidenceTitlesQuotesSupportsAndPriorHistoryAreChecked() {
        var support = new EvidenceBundle.Support("E1", "snapshot", "entry", null, null, "safe", null, "hash", 1, 1, "token=Synthet1c-Only-Value-7823");
        var item = new EvidenceBundle.Item("R1", "KNOWLEDGE", "entry", null, null, "safe", null, null, null, "hash", "hash", 1, 1, "safe", List.of(support));
        bundle("safe", List.of(item), null);
        assertThatThrownBy(() -> answers.prepareBase("workspace", "bundle", "CODEX", "model")).isInstanceOf(LibraryException.class);
        var history = List.of(new EvidenceBundle.ChatTurn("old", "digest", "safe", "xoxb-synthetic-secret"));
        bundle("safe", List.of(), new EvidenceBundle.ChatContext("thread", "old", "request", "safe", history));
        assertThatThrownBy(() -> plans.prepare("workspace", "bundle", "CODEX", "model")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> ContextPrompt.prefix(history)).isInstanceOf(LibraryException.class);
    }

    @Test void frozenContextPrefixIsBlockedAndSafePromptRemainsDeterministic() {
        bundle("safe question", List.of(), null);
        when(settings.resolve(any(), any())).thenReturn("model");
        when(settings.executionBinding(any())).thenReturn("profile");
        when(contexts.validated("workspace", "bundle")).thenReturn(Optional.empty());
        var first = answers.prepare("workspace", "bundle", "CODEX", "model");
        assertThat(answers.prepare("workspace", "bundle", "CODEX", "model")).isEqualTo(first);
        when(contexts.validated("workspace", "bundle")).thenReturn(Optional.of(new AnswerContextProvider.Context(
                "context", "conversation", 1, "CODEX", "model", "password=Synthet1c-Only-Value-7823", "old-digest")));
        assertThatThrownBy(() -> answers.prepare("workspace", "bundle", "CODEX", "model"))
                .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
    }
}
