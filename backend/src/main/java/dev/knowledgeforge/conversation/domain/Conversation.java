package dev.knowledgeforge.conversation.domain;

public record Conversation(String id, String workspaceId, String title, int revision, String createdAt) {
    public record Turn(String jobId, int position, String addedAt) {}
}
