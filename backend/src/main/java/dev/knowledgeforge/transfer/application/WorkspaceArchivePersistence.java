package dev.knowledgeforge.transfer.application;

import dev.knowledgeforge.search.domain.EvidenceBundle;

public interface WorkspaceArchivePersistence {
    void requireSupportedHistory(String workspaceId);
    EvidenceBundle saveBundle(String workspaceId, String createdAt, EvidenceBundle.Payload payload);
}
