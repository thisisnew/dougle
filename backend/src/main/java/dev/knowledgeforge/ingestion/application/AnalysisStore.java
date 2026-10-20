package dev.knowledgeforge.ingestion.application;

import dev.knowledgeforge.ingestion.domain.Analysis;
import java.util.Optional;

public interface AnalysisStore {
    void save(String entryId, Analysis analysis);
    Optional<Analysis> find(String workspaceId, String entryId);
}
