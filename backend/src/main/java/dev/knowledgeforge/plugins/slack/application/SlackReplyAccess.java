package dev.knowledgeforge.plugins.slack.application;

public interface SlackReplyAccess {
    record Scope(String workspaceId, String inboxItemId, String sourceDigest, String teamId, String channelId,
                 String threadTimestamp, String botUserId, String connectionId) {}
    final class Lease {
        private final Scope scope;
        private final String token;
        private final Runnable validate;
        public Lease(Scope scope, String token, Runnable validate) { this.scope = scope; this.token = token; this.validate = validate; }
        public Scope scope() { return scope; }
        public String botToken() { return token; }
        public void recheck() { validate.run(); }
        @Override public String toString() { return "Slack reply lease [credentials redacted]"; }
    }
    Scope snapshot(String workspaceId, String inboxItemId);
    Lease authorize(Scope expected);
}
