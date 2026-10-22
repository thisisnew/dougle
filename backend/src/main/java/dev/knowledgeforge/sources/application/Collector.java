package dev.knowledgeforge.sources.application;

import dev.knowledgeforge.sources.domain.CollectedSnapshot;

public interface Collector {
    String kind();
    void validate(String location);
    CollectedSnapshot collect(String location) throws Exception;
    default boolean usesPreviousSnapshot() { return false; }
    default CollectedSnapshot collect(String location, CollectedSnapshot previous) throws Exception {
        return collect(location);
    }
}
