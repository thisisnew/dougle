package dev.dougle.ingestion.application;

import dev.dougle.ingestion.domain.Analysis;
import java.util.Optional;

public interface AnalysisStore {
    record Failure(String entryId, String error) {}
    java.util.List<Failure> failures(String workspaceId);
    void save(String entryId, Analysis analysis);
    Optional<Analysis> find(String workspaceId, String entryId);
}
