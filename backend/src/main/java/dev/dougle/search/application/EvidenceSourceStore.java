package dev.dougle.search.application;

import dev.dougle.knowledge.application.KnowledgeStore;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceMaterial;
import java.util.List;
import java.util.Optional;

public interface EvidenceSourceStore {
    Optional<EvidenceMaterial> material(String workspaceId, String entryId);
    Optional<EvidenceMaterial> currentMaterial(String workspaceId, String entryId, String sourceId, String path);

    default <T> T withMaterial(String workspaceId, String entryId,
                               java.util.function.Function<Optional<EvidenceMaterial>, T> reader) {
        return reader.apply(material(workspaceId, entryId));
    }

    static EvidenceSourceStore legacy(KnowledgeStore store) {
        return new EvidenceSourceStore() {
            public Optional<EvidenceMaterial> material(String workspaceId, String entryId) {
                return store.material(workspaceId, entryId).map(this::convert);
            }
            public Optional<EvidenceMaterial> currentMaterial(String workspaceId, String entryId, String sourceId, String path) {
                return store.currentMaterial(workspaceId, entryId, sourceId, path).map(this::convert);
            }
            private EvidenceMaterial convert(KnowledgeStore.Material material) {
                return new EvidenceMaterial(material.entryId(), material.sourceId(), material.revision(), material.path(),
                        material.name(), material.analysisStatus(), ContentDigest.of(material.content()), material.text(), List.of());
            }
        };
    }
}
