package dev.knowledgeforge.search.domain;

import java.util.List;

public record EvidenceBundle(String id, String workspaceId, String createdAt, String digest, Payload payload) {
    public record Payload(String question, List<String> domains, boolean includeKnowledge, String status,
                          int usedCharacters, int maxCharacters, List<Item> items, List<Excluded> excluded) {}
    public record Item(String label, String kind, String entryId, String cardId, Integer version, String title,
                       String sourceId, String path, String sourceRevision, String contentHash, String textHash,
                       int startLine, int endLine, String quote, List<Support> supports) {}
    public record Support(String label, String snapshotId, String entryId, String sourceId, String path,
                          String name, String sourceRevision, String contentHash, int startLine, int endLine, String quote) {}
    public record Excluded(String kind, String entryId, String cardId, String reason) {}
    public record Summary(String id, String question, String status, int usedCharacters, String createdAt) {}
}
