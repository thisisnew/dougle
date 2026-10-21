package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.GenerationJob;
import java.util.List;
import java.util.Optional;

public interface GenerationJobStore {
    Optional<GenerationJob> find(String workspace, String id);
    List<GenerationJob> list(String workspace);
    void create(GenerationJob job);
    boolean update(String workspace, String id, String status, String resultCardId, String error);
    boolean recordCli(String workspace, String id, String version, String status, String checkedAt);
}
