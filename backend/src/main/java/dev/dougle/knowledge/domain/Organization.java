package dev.dougle.knowledge.domain;

import java.util.List;

public final class Organization {
    private Organization() {}
    public record Folder(String id, String parentId, String name, int revision) {}
    public record Placement(String cardId, String folderId, int revision) {}
    public record Catalog(List<Folder> folders, List<Placement> placements) {}
}
