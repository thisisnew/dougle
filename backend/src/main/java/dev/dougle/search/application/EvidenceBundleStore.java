package dev.dougle.search.application;

import dev.dougle.search.domain.EvidenceBundle;
import java.util.List;
import java.util.Optional;

public interface EvidenceBundleStore {
    EvidenceBundle save(String workspaceId, EvidenceBundle.Payload payload);
    Optional<EvidenceBundle> find(String workspaceId, String id);
    List<EvidenceBundle.Summary> list(String workspaceId);
    void delete(String workspaceId, String id);
}
