package dev.knowledgeforge.conversation.application;

import dev.knowledgeforge.conversation.domain.Conversation;
import java.util.List;
import java.util.Optional;

public interface ConversationStore {
    List<Conversation> list(String workspaceId);
    Optional<Conversation> find(String workspaceId, String id);
    void create(Conversation conversation);
    List<Conversation.Turn> turns(String workspaceId, String id);
    void append(String workspaceId, String id, String jobId, int position, String addedAt);
    void delete(String workspaceId, String id);
}
