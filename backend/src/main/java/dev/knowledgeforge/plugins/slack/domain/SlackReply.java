package dev.knowledgeforge.plugins.slack.domain;

public record SlackReply(String id, String workspaceId, String inboxItemId, String previewRequestId,
                         String scopeHash, String scopeJson, String body, String digest, String status,
                         String executeRequestId, int dispatchCount, String slackMessageTs, String failureCode,
                         String createdAt, String expiresAt, String updatedAt) {
    @Override public String toString() { return "Slack reply [content redacted]"; }
}
