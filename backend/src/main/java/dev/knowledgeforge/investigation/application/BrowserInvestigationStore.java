package dev.knowledgeforge.investigation.application;

import java.util.List;
import java.util.Optional;

public interface BrowserInvestigationStore {
    record Preview(String digest, String workspaceId, String bundleId, String bundleDigest,
                   BrowserCaptureScope scope, String createdAt, String expiresAt) {}
    record Job(String id, String workspaceId, String bundleId, String digest, String status,
               BrowserCaptureTransport.Snapshot result, String error, String approvedAt, String updatedAt) {
        public boolean active() { return java.util.Set.of("QUEUED", "RUNNING", "CANCELLING").contains(status); }
    }
    boolean available();
    void save(Preview preview);
    Optional<Preview> preview(String workspace, String digest);
    boolean consume(String workspace, String digest, String requestId, String now);
    Optional<Job> find(String workspace, String requestId);
    List<Job> list(String workspace, String bundleId);
    boolean update(String workspace, String requestId, String status, BrowserCaptureTransport.Snapshot result, String error);
}
