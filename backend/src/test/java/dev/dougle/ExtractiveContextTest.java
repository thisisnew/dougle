package dev.dougle;

import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.ContextPrompt;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceBundle;
import dev.dougle.search.domain.EvidenceBundle.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ExtractiveContextTest {
    private final JsonMapper json = new JsonMapper();
    private UserQuestion question(int index, String text) {
        return new UserQuestion(new UUID(0, index + 1).toString(), ContentDigest.of("source-" + index), text);
    }
    private ChatContext context(ContextPrompt.Selection selected, String parent) {
        return new ChatContext("thread", parent, "request", "current", selected.history(), selected.extractive());
    }

    @Test void rollsMoreThan100InputsWithoutTruncatingWholeQuestionsAndReportsActualHorizon() {
        var questions = new ArrayList<UserQuestion>();
        for (int index = 0; index < 140; index++) questions.add(question(index, "question " + index));
        var selection = ContextPrompt.select(questions, List.of(), List.of("current"), false);
        assertThat(selection.extractive().questions()).containsExactlyElementsOf(questions.subList(108, 140));
        assertThat(selection.extractive().totalQuestions()).isEqualTo(140);
        assertThat(selection.extractive().omittedQuestions()).isEqualTo(108);
        assertThat(ContextPrompt.serializedCharacters(context(selection, questions.getLast().bundleId()))).isLessThanOrEqualTo(8000);
    }

    @Test void omittedTargetCannotBeReconstructedFromEitherRenderedPrompt() {
        var questions = new ArrayList<UserQuestion>();
        questions.add(question(0, "가상 설명 대상으로 CEDAR-8472를 선택했어. 실제 조회 없이 정책 용어만 설명해줘."));
        for (int index = 1; index <= 32; index++) questions.add(question(index,
                index + "번째 용어 질문: 라벨 검토와 승인 검토의 정의를 설명해줘. 실제 시스템 조회는 하지 마."));
        var recent = questions.subList(28, 33).stream().map(value ->
                new ChatTurn(value.bundleId(), value.bundleDigest(), value.question(), null)).toList();
        var selection = ContextPrompt.select(questions, recent, List.of("앞서 선택한 대상"), false);
        var context = new ChatContext("thread", questions.getLast().bundleId(), "request", "앞서 선택한 대상",
                selection.history(), selection.extractive());
        assertThat(selection.extractive().omittedQuestions()).isPositive();
        assertThat(selection.extractive().totalQuestions()).isEqualTo(33);
        assertThat(ContextPrompt.serializedCharacters(context)).isLessThanOrEqualTo(8000);
        String answerPrompt = ContextPrompt.prefix(context) + "앞서 선택한 가상 대상 이름은?";
        assertThat(answerPrompt).doesNotContain("CEDAR-8472").contains("Do not claim complete memory", "omittedQuestions");
        assertThat(ContextPrompt.planPrompt(answerPrompt)).doesNotContain("CEDAR-8472");
        assertThat(questions.getFirst().question()).contains("CEDAR-8472");
    }

    @Test void escapedUtf16JsonBudgetKeepsWholeQuestionsAndDisclosesDroppedAnswers() {
        var questions = new ArrayList<UserQuestion>();
        for (int index = 0; index < 32; index++) questions.add(question(index, "\u0001".repeat(220) + "😀".repeat(10) + index));
        var answers = questions.subList(27, 32).stream().map(question -> new ChatTurn(question.bundleId(), question.bundleDigest(), question.question(), "x".repeat(1200))).toList();
        var selection = ContextPrompt.select(questions, answers, List.of("current"), false);
        var context = context(selection, questions.getLast().bundleId());
        assertThat(ContextPrompt.serializedCharacters(context)).isLessThanOrEqualTo(8000);
        assertThat(selection.extractive().omittedQuestions()).isGreaterThan(0);
        assertThat(selection.extractive().omittedAnswers()).isEqualTo(5);
        assertThat(selection.extractive().questions()).allSatisfy(question -> assertThat(questions).contains(question));
        assertThat(selection.extractive().questions().getLast()).isEqualTo(questions.getLast());
        assertThat(ContextPrompt.prefix(context)).contains("omittedQuestions", "unavailable", "legacyUnclassified", "Do not claim complete memory");
    }

    @Test void answerClippingDoesNotSplitSurrogatesAndNeverChangesOriginalSnapshot() {
        var question = question(0, "scope");
        String answer = "x".repeat(799) + "😀 tail";
        var history = new ArrayList<>(List.of(new ChatTurn(question.bundleId(), question.bundleDigest(), question.question(), answer)));
        var selection = ContextPrompt.select(List.of(question), history, List.of("current"), false);
        history.clear();
        assertThat(selection.history().getFirst().answer()).hasSize(799);
        assertThat(selection.extractive().truncatedAnswerBundleIds()).containsExactly(question.bundleId());
        assertThat(selection.history().getFirst().answer().getBytes(StandardCharsets.UTF_8)).hasSize(799);
        assertThatThrownBy(() -> selection.extractive().questions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void legacyJsonAndPromptHaveNoNewNullFieldsOrVersionChange() {
        String encoded = "{\"threadId\":\"thread\",\"parentBundleId\":null,\"requestId\":\"request\",\"retrievalQuery\":\"current\",\"history\":[]}";
        var old = json.readValue(encoded, ChatContext.class);
        assertThat(old.extractive()).isNull();
        assertThat(json.writeValueAsString(old)).isEqualTo(encoded);
        assertThat(ContentDigest.of(json.writeValueAsString(old))).isEqualTo(ContentDigest.of(encoded));
        assertThat(ContextPrompt.prefix(old)).isEqualTo(ContextPrompt.prefix(old.history()));
    }

    @Test void validatesExactSourcesAndRejectsForgedQuestionDigestAndWorkspace() {
        var payload = new Payload("original", List.of(), false, "READY", 0, 12000, List.of(), List.of(), new ChatContext("thread", null, "first", "original", List.of()));
        var source = new EvidenceBundle("source", "workspace", "now", "digest", payload);
        var original = ContextPrompt.question(source);
        var valid = context(ContextPrompt.select(List.of(original), List.of(), List.of("current"), false), source.id());
        ContextPrompt.validate("workspace", valid, ignored -> source);
        for (var forged : List.of(new UserQuestion(source.id(), "changed", "original", true), new UserQuestion(source.id(), source.digest(), "changed", true))) {
            var invalid = context(ContextPrompt.select(List.of(forged), List.of(), List.of("current"), false), source.id());
            assertThatThrownBy(() -> ContextPrompt.validate("workspace", invalid, ignored -> source)).isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> ContextPrompt.validate("other", valid, ignored -> source)).isInstanceOf(LibraryException.class);
    }

    @Test void planningAndAnswerRenderingCannotBypassCredentialGuardThroughOlderQuestions() {
        var selection = ContextPrompt.select(List.of(question(0, "password=syntheticSecret123456789")), List.of(), List.of("current"), false);
        assertThatThrownBy(() -> ContextPrompt.prefix(context(selection, selection.extractive().questions().getFirst().bundleId())))
                .isInstanceOf(LibraryException.class);
    }

    @Test void captureRequiresParentAndExactOriginalQuestion() {
        var root = context(ContextPrompt.select(List.of(), List.of(), List.of("current"), true), null);
        assertThatThrownBy(() -> ContextPrompt.validate("workspace", "original", root, ignored -> null)).isInstanceOf(LibraryException.class);
        var payload = new Payload("original", List.of(), false, "READY", 0, 12000, List.of(), List.of(), new ChatContext("thread", null, "first", "original", List.of()));
        var source = new EvidenceBundle("source", "workspace", "now", "digest", payload);
        var capture = context(ContextPrompt.select(List.of(ContextPrompt.question(source)), List.of(), List.of("current"), true), source.id());
        ContextPrompt.validate("workspace", "original", capture, ignored -> source);
        assertThatThrownBy(() -> ContextPrompt.validate("workspace", "changed", capture, ignored -> source)).isInstanceOf(LibraryException.class);
    }
}
