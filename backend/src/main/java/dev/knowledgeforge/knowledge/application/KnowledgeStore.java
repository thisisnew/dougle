package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import java.util.List;
import java.util.Optional;

public interface KnowledgeStore {
    record Material(String entryId, String sourceId, String sourceName, String sourceKind, String sourceLocation, String revision, String path,
                    String name, byte[] content, String text, String analysisStatus) {}
    record Reference(String snapshotId, int startLine, int endLine) {}
    List<Card> list(String workspaceId);
    Optional<Card> card(String workspaceId, String id);
    Optional<Version> version(String workspaceId, String id, int number);
    Optional<Material> material(String workspaceId, String entryId);
    Optional<Material> currentMaterial(String workspaceId, String entryId, String sourceId, String path);
    String capture(String workspaceId, Material material);
    void create(String workspaceId, String id, String createdAt);
    boolean advance(String workspaceId, String id, int expectedVersion);
    default void append(String id, int number, String title, String markdown, String createdAt, List<Reference> references) {
        append(id, number, title, markdown, createdAt, references, true);
    }
    void append(String id, int number, String title, String markdown, String createdAt, List<Reference> references, boolean manuallyEdited);
    void delete(String workspaceId, String id);
    Optional<byte[]> snapshotContent(String workspaceId, String cardId, String snapshotId);
}
