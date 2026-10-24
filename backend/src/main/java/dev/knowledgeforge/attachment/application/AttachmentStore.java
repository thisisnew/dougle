package dev.knowledgeforge.attachment.application;

import dev.knowledgeforge.attachment.domain.QuestionAttachment;
import java.util.Optional;

public interface AttachmentStore {
    Optional<QuestionAttachment> find(String workspaceId, String id);
    void save(QuestionAttachment attachment);
    boolean bind(String workspaceId, String id, String bundleId);
    boolean deleteUnbound(String workspaceId, String id);
    long usedBytes(String workspaceId);
    int pendingCount(String workspaceId);
    void expireUnbound(String before);
}
