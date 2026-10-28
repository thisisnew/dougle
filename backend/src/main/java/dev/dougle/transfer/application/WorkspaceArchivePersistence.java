package dev.dougle.transfer.application;

import dev.dougle.search.domain.EvidenceBundle;

public interface WorkspaceArchivePersistence {
    void requireSupportedHistory(String workspaceId);
    EvidenceBundle saveBundle(String workspaceId, String createdAt, EvidenceBundle.Payload payload);
}
