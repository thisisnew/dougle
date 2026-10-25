package dev.knowledgeforge.ingestion.application;

import dev.knowledgeforge.ingestion.domain.Analysis;
import java.util.Optional;

public interface AnalysisStore {
    record Failure(String entryId, String error) {}
    java.util.List<Failure> failures(String workspaceId);
    void save(String entryId, Analysis analysis);
    Optional<Analysis> find(String workspaceId, String entryId);
}
