package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.ProviderExecutionException.Diagnostic;
import dev.dougle.knowledge.application.ProviderExecutionException.Phase;
import dev.dougle.knowledge.application.ProviderExecutionException.Signal;
import dev.dougle.knowledge.application.ProviderExecutionException.Transport;
import dev.dougle.knowledge.application.ProviderExecutionException.AppStream;
import dev.dougle.knowledge.application.ProviderExecutionException.StreamPhase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

final class ProcessDiagnostics {
    enum AppEvent { TURN_ACCEPTED, MESSAGE_STARTED, TEXT_DELTA, MESSAGE_COMPLETED, TURN_COMPLETION_RECEIVED, COMPLETED, IGNORED_BINDING, OTHER_NOTIFICATION, PROTOCOL_INVALID }
    enum BindingMismatch { MISSING_THREAD, FOREIGN_THREAD, MISSING_TURN, FOREIGN_TURN }
    private static final Pattern HTTP_FAILURE = Pattern.compile("^ERROR: unexpected status (429|502|503|504)(?: (?:Too Many Requests|Bad Gateway|Service Unavailable|Gateway Timeout))?(?::.*)?$");
    private static final Pattern RECONNECT = Pattern.compile("^Reconnecting\\.\\.\\. [1-9][0-9]*/[1-9][0-9]* - unexpected status (?:408|429|502|503|504)(?: (?:Request Timeout|Too Many Requests|Bad Gateway|Service Unavailable|Gateway Timeout))?(?::.*)?$");
    private final Transport transport;
    private final AtomicReference<Phase> phase;
    private final AtomicReference<Signal> signal = new AtomicReference<>(Signal.NONE);
    private final AtomicInteger stdoutBytes = new AtomicInteger();
    private final AtomicInteger stderrBytes = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean promptWritten = new java.util.concurrent.atomic.AtomicBoolean();
    private final Lines stdout = new Lines();
    private final Lines stderr = new Lines();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private StreamPhase streamPhase = StreamPhase.NONE;
    private boolean turnAccepted;
    private int messages;
    private int deltas;
    private int completedMessages;
    private int matchedCompletions;
    private int ignoredBindings;
    private final int[] bindingMismatches = new int[BindingMismatch.values().length];
    private int ignoredAnswerLifecycle;
    private int ignoredOther;
    private int otherNotifications;
    private int protocolInvalid;
    private volatile int pendingLineBytes;
    private volatile boolean callbackActive;

    ProcessDiagnostics(Transport transport) {
        this.transport = transport;
        phase = new AtomicReference<>(transport == Transport.CODEX_APP_SERVER ? Phase.INITIALIZE : Phase.PROCESS_STARTED);
    }

    void promptWritten() { promptWritten.set(true); advance(Phase.PROMPT_WRITTEN); }
    private void advance(Phase next) { phase.updateAndGet(previous -> previous.ordinal() < next.ordinal() ? next : previous); }
    void appPhase(String value) {
        phase.set(switch (value) {
            case "연결 초기화" -> Phase.INITIALIZE;
            case "격리 설정 확인" -> Phase.CONFIG;
            case "대화 준비" -> Phase.THREAD;
            case "질문 접수" -> Phase.TURN;
            case "답변 생성" -> Phase.WAIT_RESPONSE;
            default -> throw new IllegalArgumentException("Unknown app-server phase");
        });
    }
    void appStdoutBytes(int count) { stdoutBytes.set(count); }
    void appPendingLineBytes(int count) { pendingLineBytes = Math.max(0, Math.min(262144, count)); }
    void appCallback(boolean active) { callbackActive = active; }
    synchronized void appEvent(AppEvent event) {
        switch (event) {
            case TURN_ACCEPTED -> { turnAccepted = true; streamPhase = StreamPhase.TURN_ACCEPTED; }
            case MESSAGE_STARTED -> { messages = increment(messages); streamPhase = StreamPhase.MESSAGE_STARTED; }
            case TEXT_DELTA -> { deltas = increment(deltas); streamPhase = StreamPhase.TEXT_DELTA; }
            case MESSAGE_COMPLETED -> { completedMessages = increment(completedMessages); streamPhase = StreamPhase.MESSAGE_COMPLETED; }
            case TURN_COMPLETION_RECEIVED -> { matchedCompletions = increment(matchedCompletions); streamPhase = StreamPhase.TURN_COMPLETION_RECEIVED; }
            case COMPLETED -> streamPhase = StreamPhase.COMPLETED;
            case IGNORED_BINDING -> ignoredBindings = increment(ignoredBindings);
            case OTHER_NOTIFICATION -> otherNotifications = increment(otherNotifications);
            case PROTOCOL_INVALID -> {
                if (streamPhase != StreamPhase.PROTOCOL_INVALID) protocolInvalid = increment(protocolInvalid);
                streamPhase = StreamPhase.PROTOCOL_INVALID;
            }
        }
    }
    private static int increment(int count) { return Math.min(1048576, count + 1); }
    synchronized void ignoredBinding(BindingMismatch reason, boolean answerLifecycle) {
        ignoredBindings = increment(ignoredBindings);
        bindingMismatches[reason.ordinal()] = increment(bindingMismatches[reason.ordinal()]);
        if (answerLifecycle) ignoredAnswerLifecycle = increment(ignoredAnswerLifecycle);
        else ignoredOther = increment(ignoredOther);
    }
    void stdout(byte[] bytes, int count) {
        stdoutBytes.addAndGet(count);
        if (transport == Transport.CODEX_EXEC) stdout.accept(bytes, count, line -> {
            try {
                var value = mapper.readTree(line);
                if (value == null || !value.isObject()) return;
                switch (value.path("type").asString("")) {
                    case "thread.started" -> advance(Phase.THREAD_STARTED);
                    case "turn.started" -> advance(Phase.TURN_STARTED);
                    case "turn.completed" -> advance(Phase.TURN_COMPLETED);
                    default -> { }
                }
            } catch (RuntimeException ignored) { }
        });
    }
    void stderr(byte[] bytes, int count) {
        stderrBytes.addAndGet(count);
        stderr.accept(bytes, count, line -> {
            var failure = HTTP_FAILURE.matcher(line);
            if (failure.matches()) signal.set(failure.group(1).equals("429") ? Signal.RATE_LIMIT : Signal.PROVIDER_UNAVAILABLE);
            else if (RECONNECT.matcher(line).matches()) signal.set(Signal.NETWORK_RECONNECT);
        });
    }
    synchronized Diagnostic snapshot() {
        var stream = transport == Transport.CODEX_APP_SERVER ? new AppStream(streamPhase, turnAccepted, messages, deltas,
                completedMessages, matchedCompletions, ignoredBindings, otherNotifications, protocolInvalid, pendingLineBytes, callbackActive,
                new dev.dougle.knowledge.application.ProviderExecutionException.IgnoredEvents(
                        bindingMismatches[0], bindingMismatches[1], bindingMismatches[2], bindingMismatches[3],
                        ignoredAnswerLifecycle, ignoredOther)) : null;
        return new Diagnostic(transport, phase.get(), signal.get(), promptWritten.get(), stdoutBytes.get(), stderrBytes.get(), stream);
    }

    private static final class Lines {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean oversized;
        void accept(byte[] bytes, int count, java.util.function.Consumer<String> consumer) {
            for (int offset = 0; offset < count; offset++) {
                if (bytes[offset] == '\n') {
                    if (!oversized) consumer.accept(line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", ""));
                    line.reset();
                    oversized = false;
                } else if (!oversized) {
                    if (line.size() < 8192) line.write(bytes[offset]);
                    else { line.reset(); oversized = true; }
                }
            }
        }
    }
}
