package dev.knowledgeforge.search.domain;

import java.util.List;

public record BundleValidation(String bundleId, String digest, String checkedAt, String status,
                               boolean eligible, List<Item> items) {
    public record Item(String label, String status, String currentEntryId, String currentSourceRevision,
                       List<Support> supports) {}
    public record Support(String label, String status) {}
}
