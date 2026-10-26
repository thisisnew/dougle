package dev.knowledgeforge.search.application;

import java.time.Instant;
import java.util.List;

public record LocalSearchSnapshot(String workspaceId, String jobId, String query, Instant expiresAt, List<Entry> entries) {
    public record Entry(SearchIndex.Hit hit, String contentHash) {}

    public LocalSearchSnapshot {
        entries = List.copyOf(entries);
        if (workspaceId == null || jobId == null || query == null || query.isBlank() || query.length() > 256
                || expiresAt == null || entries.isEmpty() || entries.size() > 5) {
            throw new IllegalArgumentException("Invalid local search snapshot");
        }
        int characters = 0;
        var identifiers = new java.util.HashSet<String>();
        for (var entry : entries) {
            var hit = entry.hit();
            if (!"ORIGINAL".equals(hit.kind()) || !hit.bodyMatch() || !identifiers.add(hit.entryId())
                    || hit.snippet() == null || hit.snippet().isBlank() || hit.snippet().length() > 6000
                    || entry.contentHash() == null || !entry.contentHash().matches("[a-f0-9]{64}")
                    || hit.textHash() == null || !hit.textHash().matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid local search snapshot evidence");
            }
            characters += hit.snippet().length();
        }
        if (characters > 12000) throw new IllegalArgumentException("Local search snapshot budget exceeded");
    }
}
