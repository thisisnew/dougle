package dev.knowledgeforge.knowledge.domain;

public record GenerationJob(String id, String workspaceId, String sourceCardId, String provider,
                            int expectedVersion, String digest, String status, String resultCardId,
                            String error, String createdAt, String updatedAt, String requestedModel) {
    public boolean active() {
        return status.equals("QUEUED") || status.equals("RUNNING") || status.equals("CANCELLING");
    }
}
