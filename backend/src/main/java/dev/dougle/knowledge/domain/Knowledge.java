package dev.dougle.knowledge.domain;

import java.util.List;

public final class Knowledge {
    private Knowledge() {}

    public record Card(String id, String workspaceId, String title, int latestVersion, String createdAt) {}
    public record Version(int number, String title, String markdown, String status,
                          boolean manuallyEdited, String createdAt, List<Evidence> evidence) {}
    public record Document(Card card, Version version) {}
    public record Evidence(String snapshotId, String entryId, String sourceId, String sourceName, String sourceKind, String sourceLocation,
                           String sourceRevision, String path, String name, String contentHash,
                           int startLine, int endLine, String quote, String freshness, String capturedAt) {}
    public record Citation(String entryId, int startLine, int endLine) {}
}
