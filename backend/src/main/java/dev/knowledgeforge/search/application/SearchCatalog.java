package dev.knowledgeforge.search.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface SearchCatalog {
    record Change(long sequence, String entryId) {}
    void each(Consumer<SearchIndex.Document> consumer);
    List<Change> changes();
    Optional<SearchIndex.Document> document(String id);
    void acknowledge(long sequence);
    boolean exists(String workspaceId, String entryId);
}
