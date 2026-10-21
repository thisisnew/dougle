package dev.knowledgeforge.conversation.application;

import dev.knowledgeforge.conversation.domain.AnswerApproval;
import java.util.Optional;

public interface AnswerApprovalStore {
    Optional<AnswerApproval> find(String bundleId);
    void save(AnswerApproval approval);
    void revoke(String bundleId, String id, String revokedAt);
}
