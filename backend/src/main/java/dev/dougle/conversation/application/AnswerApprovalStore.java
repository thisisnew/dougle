package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.AnswerApproval;
import java.util.Optional;

public interface AnswerApprovalStore {
    Optional<AnswerApproval> find(String bundleId);
    void save(AnswerApproval approval);
    void revoke(String bundleId, String id, String revokedAt);
}
