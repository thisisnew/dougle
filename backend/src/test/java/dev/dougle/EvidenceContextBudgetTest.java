package dev.dougle;

import dev.dougle.attachment.application.AttachmentService;
import dev.dougle.attachment.domain.QuestionAttachment;
import dev.dougle.knowledge.application.KnowledgeStore;
import dev.dougle.knowledge.application.PublicationStore;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.search.application.*;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceBundle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceContextBudgetTest {
    final LibraryStore library = mock(LibraryStore.class);
    final KnowledgeStore knowledge = mock(KnowledgeStore.class);
    final EvidenceBundleStore bundles = mock(EvidenceBundleStore.class);
    final SearchCatalog catalog = mock(SearchCatalog.class);
    final AttachmentService attachments = mock(AttachmentService.class);
    final EvidenceBundleWriter writer = new EvidenceBundleWriter(knowledge, mock(PublicationStore.class), library, bundles, catalog, attachments);

    EvidenceContextBudgetTest() {
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
        when(bundles.save(eq("workspace"), any())).thenAnswer(call -> new EvidenceBundle("saved", "workspace", "now", "digest", call.getArgument(1)));
    }

    EvidenceBundle.ChatContext context() {
        var questions = IntStream.range(0, 32).mapToObj(index -> new EvidenceBundle.UserQuestion("source-" + index,
                ContentDigest.of("source-" + index), "question " + index + " \"\\😀".repeat(20))).toList();
        var selected = ContextPrompt.select(questions, List.of(), List.of("current"), false);
        return new EvidenceBundle.ChatContext("thread", "source-31", "request", "current", selected.history(), selected.extractive());
    }

    @Test void rejectsExplicitAttachmentsBeforeSaveOrBindingInsteadOfSilentlyTrimming() {
        var context = context();
        for (String text : List.of("x".repeat(ContextPrompt.evidenceCharacters(context) + 1), "\u0001".repeat(3000))) {
            var attached = new QuestionAttachment("attachment", "workspace", null, "fixture.txt", "text/plain",
                    text.getBytes(StandardCharsets.UTF_8), ContentDigest.of(text), text, false, "now");
            when(attachments.pending("workspace", List.of("attachment"))).thenReturn(List.of(attached));
            assertThatThrownBy(() -> writer.capture("workspace", "current", List.of(), false, List.of(), List.of("attachment"), context))
                    .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(413));
        }
        verify(bundles, never()).save(anyString(), any());
        verify(attachments, never()).bind(anyString(), anyList(), anyString());
    }

    @Test void excludesJsonExpandedEvidenceWithExplicitReasonBeforeSavingImmutablePayload() {
        var context = context();
        String text = "\u0001".repeat(3000);
        String digest = ContentDigest.of(text);
        when(catalog.document("source")).thenReturn(Optional.of(new SearchIndex.Document("source", "workspace", "fixture", text, "", null, "local", "fixture")));
        when(knowledge.material("workspace", "source")).thenReturn(Optional.of(new KnowledgeStore.Material("source", null, null, null, null, null,
                "fixture", "fixture", text.getBytes(StandardCharsets.UTF_8), text, "READY")));
        var hit = new SearchIndex.Hit("source", "fixture", null, "local", "fixture", text, 1, 1, 1, true, "ORIGINAL", null, null, List.of(), "CURRENT", digest);
        assertThat(text.length()).isLessThanOrEqualTo(ContextPrompt.evidenceCharacters(context));
        var result = writer.capture("workspace", "current", List.of(), false, List.of(hit), List.of(), context);
        assertThat(result.payload().items()).isEmpty();
        assertThat(result.payload().excluded()).extracting(EvidenceBundle.Excluded::reason).containsExactly("CONTEXT_PROMPT_LIMIT");
        assertThat(result.payload().maxCharacters()).isEqualTo(ContextPrompt.evidenceCharacters(context));
        assertThat(EvidenceAnswerPreview.renderPrompt("current", result.payload().items(), context).length()).isLessThanOrEqualTo(20000);
    }

    @Test void legacyRawEvidenceBudgetRemainsTwelveThousandCharacters() {
        assertThat(ContextPrompt.evidenceCharacters(null)).isEqualTo(12000);
        assertThat(ContextPrompt.evidenceCharacters(new EvidenceBundle.ChatContext("thread", null, "request", "current", List.of()))).isEqualTo(12000);
    }

    @Test void plannerWrapperBudgetRejectsAnswerThatWouldFitAlone() {
        String answer = "\"".repeat(10000) + "😀";
        assertThat(answer.length()).isLessThan(20000);
        assertThat(ContextPrompt.planPrompt(answer).length()).isGreaterThan(20000);
        assertThat(ContextPrompt.withinRequestBudgets(answer)).isFalse();
        assertThat(ContextPrompt.withinRequestBudgets("short reference")).isTrue();
    }
}
