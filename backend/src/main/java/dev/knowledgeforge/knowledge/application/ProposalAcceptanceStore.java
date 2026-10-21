package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.ProposalAcceptance;
import java.util.Optional;

public interface ProposalAcceptanceStore {
    Optional<ProposalAcceptance> find(String workspace, String job);
    void save(String workspace, String job, ProposalAcceptance acceptance);
}
