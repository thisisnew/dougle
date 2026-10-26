package dev.knowledgeforge.investigation.application;

import java.util.List;
import java.util.Optional;

public interface InvestigationPlanStore {
    record Preview(String workspaceId, String bundleId, String provider, String requestedModel, String digest,
                   String bindingDigest, String prompt, String createdAt, String expiresAt,
                   String continuationRoot, String captureJobId) {
        public Preview(String workspaceId, String bundleId, String provider, String requestedModel, String digest,
                       String bindingDigest, String prompt, String createdAt, String expiresAt) {
            this(workspaceId, bundleId, provider, requestedModel, digest, bindingDigest, prompt, createdAt, expiresAt, null, null);
        }
    }
    record Capture(String workspaceId, String jobId, String bundleId, String bundleDigest,
                   String rootQuestionBundleId, String requestId) {}
    record Job(String id, String workspaceId, String bundleId, String provider, String requestedModel, String digest,
               String status, String decision, String query, String output, boolean truncated, String toolStatus,
               String error, String approvedAt, String updatedAt) {
        public boolean active() { return java.util.Set.of("QUEUED", "RUNNING", "CANCELLING").contains(status); }
    }
    void save(Preview preview);
    Optional<Preview> preview(String workspaceId, String digest);
    boolean consume(String workspaceId, String digest, String requestId, String now);
    void saveCapture(Capture capture);
    Optional<Capture> capture(String workspaceId, String bundleId);
    Optional<Capture> captureForJob(String workspaceId, String jobId);
    boolean claimContinuation(String workspaceId, String rootQuestionBundleId, String requestId);
    boolean continuationConsumed(String workspaceId, String rootQuestionBundleId);
    Optional<Job> find(String workspaceId, String requestId);
    List<Job> list(String workspaceId, String bundleId);
    boolean update(String workspaceId, String requestId, String status, String decision, String query,
                   String output, boolean truncated, String toolStatus, String error);
}
