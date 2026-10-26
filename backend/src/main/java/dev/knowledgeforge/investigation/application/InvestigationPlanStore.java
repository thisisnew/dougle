package dev.knowledgeforge.investigation.application;

import java.util.List;
import java.util.Optional;

public interface InvestigationPlanStore {
    record Preview(String workspaceId, String bundleId, String provider, String requestedModel, String digest,
                   String bindingDigest, String prompt, String createdAt, String expiresAt) {}
    record Job(String id, String workspaceId, String bundleId, String provider, String requestedModel, String digest,
               String status, String decision, String query, String output, boolean truncated, String toolStatus,
               String error, String approvedAt, String updatedAt) {
        public boolean active() { return java.util.Set.of("QUEUED", "RUNNING", "CANCELLING").contains(status); }
    }
    void save(Preview preview);
    Optional<Preview> preview(String workspaceId, String digest);
    boolean consume(String workspaceId, String digest, String requestId, String now);
    Optional<Job> find(String workspaceId, String requestId);
    List<Job> list(String workspaceId, String bundleId);
    boolean update(String workspaceId, String requestId, String status, String decision, String query,
                   String output, boolean truncated, String toolStatus, String error);
}
