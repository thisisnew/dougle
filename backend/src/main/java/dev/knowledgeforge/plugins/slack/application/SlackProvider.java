package dev.knowledgeforge.plugins.slack.application;

import dev.knowledgeforge.plugins.slack.domain.SlackEvent;

public interface SlackProvider {
    enum FailureKind { AUTH_REQUIRED, PERMISSION_DENIED }
    final class AccessFailure extends RuntimeException {
        private final FailureKind kind;
        public AccessFailure(FailureKind kind) { super("Slack access unavailable"); this.kind = kind; }
        public FailureKind kind() { return kind; }
    }
    record Identity(String teamId, String botUserId) {
        public Identity {
            if (teamId == null || !teamId.matches("T[A-Z0-9]{1,63}")
                    || botUserId == null || !botUserId.matches("[UW][A-Z0-9]{1,63}")) {
                throw new IllegalArgumentException("Invalid Slack identity");
            }
        }
    }
    interface Listener {
        void ready();
        boolean event(SlackEvent event);
        void closed();
        default boolean accessLost(String teamId, String userId) { return false; }
    }
    interface Session extends AutoCloseable { @Override void close(); }
    Identity authenticate(String botToken);
    Session open(String appToken, Listener listener);
}
