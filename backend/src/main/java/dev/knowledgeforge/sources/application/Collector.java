package dev.knowledgeforge.sources.application;

import dev.knowledgeforge.sources.domain.CollectedSnapshot;

public interface Collector {
    String kind();
    void validate(String location);
    CollectedSnapshot collect(String location) throws Exception;
}
