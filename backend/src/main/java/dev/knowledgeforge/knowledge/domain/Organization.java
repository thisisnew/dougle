package dev.knowledgeforge.knowledge.domain;

import java.util.List;

public final class Organization {
    private Organization() {}
    public record Folder(String id, String parentId, String name) {}
    public record Placement(String cardId, String folderId, int revision) {}
    public record Catalog(List<Folder> folders, List<Placement> placements) {}
}
