package dev.dougle.conversation.domain;

public record AnswerJob(String id, String workspaceId, String bundleId, String approvalId, String provider,
                        String requestedModel, String digest, String status, String answer, String error,
                        String createdAt, String updatedAt) {
    public boolean active() { return java.util.Set.of("QUEUED", "RUNNING", "CANCELLING").contains(status); }
}
