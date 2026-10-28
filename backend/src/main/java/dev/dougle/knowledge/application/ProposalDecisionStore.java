package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.ProposalDecision;
import java.util.List;

public interface ProposalDecisionStore {
    List<ProposalDecision> history(String workspace, String job);
    boolean append(String workspace, String job, ProposalDecision decision);
}
