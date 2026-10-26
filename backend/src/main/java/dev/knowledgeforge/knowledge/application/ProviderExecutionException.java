package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.library.application.LibraryException;

public final class ProviderExecutionException extends LibraryException {
    public enum Transport { CODEX_EXEC, CLI_EXEC, CODEX_APP_SERVER }
    public enum Phase { PROCESS_STARTED, PROMPT_WRITTEN, THREAD_STARTED, TURN_STARTED, TURN_COMPLETED,
        INITIALIZE, CONFIG, THREAD, TURN, WAIT_RESPONSE }
    public enum Signal { NONE, RATE_LIMIT, NETWORK_RECONNECT, PROVIDER_UNAVAILABLE }
    public enum StreamPhase { NONE, TURN_ACCEPTED, MESSAGE_STARTED, TEXT_DELTA, MESSAGE_COMPLETED, TURN_COMPLETION_RECEIVED, COMPLETED, PROTOCOL_INVALID }
    public record IgnoredEvents(int missingThread, int foreignThread, int missingTurn, int foreignTurn,
                                int answerLifecycle, int other) {
        public IgnoredEvents {
            for (int count : new int[]{missingThread, foreignThread, missingTurn, foreignTurn, answerLifecycle, other}) {
                if (count < 0 || count > 1048576) throw new IllegalArgumentException("Invalid ignored event count");
            }
        }
    }
    public record AppStream(StreamPhase phase, boolean turnAccepted, int messages, int deltas, int completedMessages,
                            int matchedCompletions, int ignoredBindings, int otherNotifications, int protocolInvalid,
                            int pendingLineBytes, boolean callbackActive, IgnoredEvents ignoredEvents) {
        public AppStream(StreamPhase phase, boolean turnAccepted, int messages, int deltas, int completedMessages,
                         int matchedCompletions, int ignoredBindings, int otherNotifications, int protocolInvalid,
                         int pendingLineBytes, boolean callbackActive) {
            this(phase, turnAccepted, messages, deltas, completedMessages, matchedCompletions, ignoredBindings,
                    otherNotifications, protocolInvalid, pendingLineBytes, callbackActive, null);
        }
        public AppStream {
            java.util.Objects.requireNonNull(phase);
            for (int count : new int[]{messages, deltas, completedMessages, matchedCompletions, ignoredBindings, otherNotifications, protocolInvalid}) {
                if (count < 0 || count > 1048576) throw new IllegalArgumentException("Invalid stream count");
            }
            if (pendingLineBytes < 0 || pendingLineBytes > 262144) throw new IllegalArgumentException("Invalid pending line size");
        }
    }
    public record Diagnostic(Transport transport, Phase phase, Signal stderrSignal, boolean execStdinWritten, int stdoutBytes, int stderrBytes, AppStream appStream) {
        public Diagnostic(Transport transport, Phase phase, Signal stderrSignal, boolean execStdinWritten, int stdoutBytes, int stderrBytes) {
            this(transport, phase, stderrSignal, execStdinWritten, stdoutBytes, stderrBytes, null);
        }
        public Diagnostic {
            java.util.Objects.requireNonNull(transport);
            java.util.Objects.requireNonNull(phase);
            java.util.Objects.requireNonNull(stderrSignal);
            if (stdoutBytes < 0 || stderrBytes < 0) throw new IllegalArgumentException("Invalid byte counts");
        }
        public String safeSummary() {
            return "transport=" + transport + ", phase=" + phase + ", stderrSignal=" + stderrSignal
                    + ", execStdinWritten=" + execStdinWritten + ", stdoutBytes=" + stdoutBytes + ", stderrBytes=" + stderrBytes
                    + (appStream == null ? "" : ", appStream=" + appStream);
        }
    }
    private final Diagnostic diagnostic;

    public ProviderExecutionException(int status, String message, Diagnostic diagnostic) {
        super(status, message + " [" + diagnostic.safeSummary() + "]");
        this.diagnostic = diagnostic;
    }

    public Diagnostic diagnostic() { return diagnostic; }
}
