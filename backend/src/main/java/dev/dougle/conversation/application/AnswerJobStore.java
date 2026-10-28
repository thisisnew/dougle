package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.AnswerJob;
import java.util.List;
import java.util.Optional;

public interface AnswerJobStore {
    Optional<AnswerJob> find(String workspaceId, String id);
    Optional<AnswerJob> byApproval(String approvalId);
    List<AnswerJob> list(String workspaceId);
    void create(AnswerJob job);
    boolean update(String workspaceId, String id, String status, String answer, String error);
}
