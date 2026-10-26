package dev.knowledgeforge.knowledge.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CodexTextStreamTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String FINAL = "{\"title\":\"fixture\",\"claims\":[]}";
    private final List<String> snapshots = new ArrayList<>();
    private final CodexTextStream stream = new CodexTextStream("thread", "turn", snapshots::add);

    @Test void diagnosticsCountBindingsAndLifecycleWithoutRetainingContentOrIdentifiers() throws Exception {
        var diagnostics = new ProcessDiagnostics(dev.knowledgeforge.knowledge.application.ProviderExecutionException.Transport.CODEX_APP_SERVER);
        var observed = new CodexTextStream("thread", "turn", ignored -> {}, diagnostics);
        observed.accept(event("turn/completed", Map.of("turn", Map.of("id", "private-turn", "status", "completed"))));
        observed.accept(end("completed").replace("\"thread\"", "\"private-thread\""));
        observed.accept(event("private-method", Map.of("secret", "private-content")));
        observed.accept(item("item/started", "private-item", "final_answer", ""));
        observed.accept(delta("private-item", "private-content"));
        observed.accept(item("item/completed", "private-item", "final_answer", FINAL));
        observed.accept(end("completed"));
        var progress = diagnostics.snapshot().appStream();
        assertThat(progress.messages()).isEqualTo(1);
        assertThat(progress.deltas()).isEqualTo(1);
        assertThat(progress.completedMessages()).isEqualTo(1);
        assertThat(progress.matchedCompletions()).isEqualTo(1);
        assertThat(progress.ignoredBindings()).isEqualTo(2);
        assertThat(progress.otherNotifications()).isEqualTo(1);
        assertThat(progress.protocolInvalid()).isZero();
        assertThat(progress.callbackActive()).isFalse();
        assertThat(progress.phase()).isEqualTo(dev.knowledgeforge.knowledge.application.ProviderExecutionException.StreamPhase.COMPLETED);
        assertThat(diagnostics.snapshot().safeSummary()).doesNotContain("private", "fixture", "claims", "threadId", "turnId", "itemId");
    }

    @Test void matchedButInvalidCompletionIsNotSuccess() throws Exception {
        var diagnostics = new ProcessDiagnostics(dev.knowledgeforge.knowledge.application.ProviderExecutionException.Transport.CODEX_APP_SERVER);
        var observed = new CodexTextStream("thread", "turn", ignored -> {}, diagnostics);
        assertThatThrownBy(() -> observed.accept(end("completed"))).isInstanceOf(IOException.class);
        assertThat(observed.completed()).isFalse();
        assertThat(diagnostics.snapshot().appStream().matchedCompletions()).isEqualTo(1);
        assertThat(diagnostics.snapshot().appStream().protocolInvalid()).isEqualTo(1);
    }

    @Test void ignoredEventReasonsSeparateMissingBindingsFromForeignAnswerEventsWithoutLeakingValues() throws Exception {
        var diagnostics = new ProcessDiagnostics(dev.knowledgeforge.knowledge.application.ProviderExecutionException.Transport.CODEX_APP_SERVER);
        var observed = new CodexTextStream("thread", "turn", snapshots::add, diagnostics);
        observed.accept(MAPPER.writeValueAsString(Map.of("method", "private-secret-method", "params", Map.of("secret", "private-value"))));
        observed.accept(MAPPER.writeValueAsString(Map.of("method", "thread/status/changed", "params", Map.of("threadId", "thread"))));
        observed.accept(delta("private-item", "private-content").replace("\"thread\"", "\"private-thread\""));
        observed.accept(event("turn/completed", Map.of("turn", Map.of("id", "private-turn", "status", "completed"))));
        var snapshot = diagnostics.snapshot();
        var ignored = snapshot.appStream().ignoredEvents();
        assertThat(ignored.missingThread()).isEqualTo(1);
        assertThat(ignored.foreignThread()).isEqualTo(1);
        assertThat(ignored.missingTurn()).isEqualTo(1);
        assertThat(ignored.foreignTurn()).isEqualTo(1);
        assertThat(ignored.answerLifecycle()).isEqualTo(2);
        assertThat(ignored.other()).isEqualTo(2);
        assertThat(snapshot.appStream().ignoredBindings()).isEqualTo(4);
        assertThat(snapshot.safeSummary()).doesNotContain("private", "secret", "threadId", "turnId");
        assertThat(snapshots).isEmpty();
        assertThat(observed.completed()).isFalse();
        observed.accept(item("item/started", "answer", "final_answer", ""));
        observed.accept(item("item/completed", "answer", "final_answer", FINAL));
        observed.accept(end("completed"));
        assertThat(observed.result()).isEqualTo(FINAL);
        assertThat(snapshot.appStream().messages()).isZero();
    }

    private String event(String method, Map<String, Object> body) {
        var params = new java.util.HashMap<>(body);
        params.put("threadId", "thread");
        params.put("turnId", "turn");
        return MAPPER.writeValueAsString(Map.of("method", method, "params", params));
    }

    private String item(String method, String id, String phase, String text) {
        var item = new java.util.HashMap<String, Object>();
        item.put("type", "agentMessage"); item.put("id", id); item.put("text", text);
        if (phase != null) item.put("phase", phase);
        return event(method, Map.of("item", item));
    }

    private String delta(String id, String text) {
        return event("item/agentMessage/delta", Map.of("itemId", id, "delta", text));
    }

    private String end(String status) {
        return event("turn/completed", Map.of("turn", Map.of("id", "turn", "status", status, "items", List.of())));
    }

    private void completedItem(String phase) throws IOException {
        stream.accept(item("item/started", "answer", phase, ""));
        stream.accept(item("item/completed", "answer", phase, FINAL));
    }

    @Test
    void exposesSnapshotsButOnlyReturnsCompletedAuthoritativeText() throws Exception {
        stream.accept(item("item/started", "answer", "final_answer", ""));
        stream.accept(delta("answer", "가상")); stream.accept(delta("answer", " 출력"));
        assertThat(snapshots).containsExactly("", "가상", "가상 출력");
        stream.accept(item("item/completed", "answer", "final_answer", FINAL));
        assertThatThrownBy(stream::result).isInstanceOf(IOException.class);
        stream.accept(end("completed"));
        assertThat(stream.result()).isEqualTo(FINAL);
        assertThat(snapshots.getLast()).isEmpty();
    }

    @Test
    void acceptsLegacyUnknownPhaseOnlyAfterSuccessfulTurn() throws Exception {
        completedItem(null); stream.accept(end("completed"));
        assertThat(stream.result()).isEqualTo(FINAL);
    }

    @Test
    void ignoresOtherThreadsTurnsReasoningAndToolArguments() throws Exception {
        stream.accept(delta("unknown", "secret").replace("\"thread\"", "\"other\""));
        stream.accept(delta("unknown", "secret").replace("\"turn\"", "\"other\""));
        stream.accept(event("item/reasoning/textDelta", Map.of("delta", "secret")));
        stream.accept(event("item/tool/callDelta", Map.of("delta", "secret")));
        stream.accept(event("item/started", Map.of("item", Map.of("type", "reasoning", "id", "reason"))));
        assertThat(snapshots).isEmpty();
    }

    @Test
    void commentaryNeverBecomesAnswerOrVisibleText() throws Exception {
        stream.accept(item("item/started", "answer", "commentary", ""));
        stream.accept(delta("answer", "hidden"));
        stream.accept(item("item/completed", "answer", "commentary", FINAL));
        assertThat(snapshots).containsExactly("");
        assertThatThrownBy(() -> stream.accept(end("completed"))).isInstanceOf(IOException.class);
    }

    @Test
    void partialAnswerAloneCannotCompleteTurn() throws Exception {
        completedItem("partial_answer");
        assertThatThrownBy(() -> stream.accept(end("completed"))).isInstanceOf(IOException.class);
    }

    @Test
    void newItemsReplaceInsteadOfConcatenatingDistinctMessages() throws Exception {
        stream.accept(item("item/started", "first", "partial_answer", ""));
        stream.accept(delta("first", "first"));
        stream.accept(item("item/completed", "first", "partial_answer", "first"));
        stream.accept(item("item/started", "answer", "final_answer", ""));
        stream.accept(delta("answer", "second"));
        stream.accept(item("item/completed", "answer", "final_answer", FINAL));
        stream.accept(end("completed"));
        assertThat(snapshots).containsExactly("", "first", "", "second", "");
        assertThat(stream.result()).isEqualTo(FINAL);
    }

    @Test
    void rejectsUnboundOverlappingDuplicateAndLateItems() throws Exception {
        for (String invalid : List.of(delta("missing", "text"), item("item/completed", "missing", null, FINAL))) {
            var fresh = new CodexTextStream("thread", "turn", value -> {});
            assertThatThrownBy(() -> fresh.accept(invalid)).isInstanceOf(IOException.class);
        }
        completedItem("final_answer");
        assertThatThrownBy(() -> stream.accept(delta("answer", "late"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> stream.accept(end("completed"))).isInstanceOf(IOException.class);
        var overlap = new CodexTextStream("thread", "turn", value -> {});
        overlap.accept(item("item/started", "first", null, ""));
        assertThatThrownBy(() -> overlap.accept(item("item/started", "second", null, ""))).isInstanceOf(IOException.class);
        var duplicate = new CodexTextStream("thread", "turn", value -> {});
        duplicate.accept(item("item/started", "first", null, ""));
        duplicate.accept(item("item/completed", "first", null, FINAL));
        assertThatThrownBy(() -> duplicate.accept(item("item/started", "first", null, ""))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsServerRequestsAndMalformedEnvelopesWithoutLeakingContents() {
        for (String invalid : List.of("not-json-secret", "[]", "{}", "{\"id\":1,\"method\":\"item/tool/requestApproval\",\"params\":{}}",
                "{\"id\":2,\"result\":{}}", "{\"method\":\"test\",\"params\":null}")) {
            var fresh = new CodexTextStream("thread", "turn", value -> {});
            assertThatThrownBy(() -> fresh.accept(invalid)).isInstanceOf(IOException.class).hasMessage("Invalid Codex stream").hasNoCause();
            assertThatThrownBy(fresh::result).isInstanceOf(IOException.class);
        }
    }

    @Test
    void failedInterruptedAndErrorTurnsDiscardCompletedCandidate() throws Exception {
        for (String status : List.of("failed", "interrupted", "inProgress")) {
            var fresh = new CodexTextStream("thread", "turn", value -> {});
            fresh.accept(item("item/started", "answer", null, ""));
            fresh.accept(item("item/completed", "answer", null, FINAL));
            assertThatThrownBy(() -> fresh.accept(end(status))).isInstanceOf(IOException.class);
            assertThatThrownBy(fresh::result).isInstanceOf(IOException.class);
        }
        completedItem(null);
        assertThatThrownBy(() -> stream.accept(event("error", Map.of("message", "secret")))).isInstanceOf(IOException.class);
        assertThatThrownBy(stream::result).isInstanceOf(IOException.class);
    }

    @Test
    void capsPartialFinalLineAndCumulativeInput() throws Exception {
        stream.accept(item("item/started", "answer", null, ""));
        assertThatThrownBy(() -> stream.accept(delta("answer", "x".repeat(20001)))).isInstanceOf(IOException.class);
        assertThat(snapshots).containsExactly("");
        var largeFinal = new CodexTextStream("thread", "turn", value -> {});
        largeFinal.accept(item("item/started", "answer", null, ""));
        assertThatThrownBy(() -> largeFinal.accept(item("item/completed", "answer", null, "x".repeat(65537)))).isInstanceOf(IOException.class);
        var largeLine = new CodexTextStream("thread", "turn", value -> {});
        assertThatThrownBy(() -> largeLine.accept(" ".repeat(262145))).isInstanceOf(IOException.class);
        var total = new CodexTextStream("thread", "turn", value -> {});
        for (int count = 0; count < 4; count++) total.accept(" ".repeat(262144));
        assertThatThrownBy(() -> total.accept(" ")).isInstanceOf(IOException.class);
    }

    @Test
    void terminalDuplicateOrConsumerFailureCannotReturnSuccess() throws Exception {
        completedItem(null); stream.accept(end("completed"));
        assertThatThrownBy(() -> stream.accept(end("completed"))).isInstanceOf(IOException.class);
        assertThatThrownBy(stream::result).isInstanceOf(IOException.class);
        var brokenConsumer = new CodexTextStream("thread", "turn", value -> { throw new IllegalStateException("secret"); });
        assertThatThrownBy(() -> brokenConsumer.accept(item("item/started", "answer", null, "")))
                .isInstanceOf(IOException.class).hasMessage("Invalid Codex stream").hasNoCause();
    }

    @Test
    void rejectsPhaseChangesAndTooManyItems() throws Exception {
        stream.accept(item("item/started", "answer", "commentary", ""));
        assertThatThrownBy(() -> stream.accept(item("item/completed", "answer", "final_answer", FINAL))).isInstanceOf(IOException.class);
        var many = new CodexTextStream("thread", "turn", value -> {});
        for (int count = 0; count < 64; count++) {
            many.accept(item("item/started", "item-" + count, null, ""));
            many.accept(item("item/completed", "item-" + count, null, FINAL));
        }
        assertThatThrownBy(() -> many.accept(item("item/started", "overflow", null, ""))).isInstanceOf(IOException.class);
    }
}
