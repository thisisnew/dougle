package dev.knowledgeforge.conversation.application;

import dev.knowledgeforge.conversation.domain.ConversationRequest;
import java.util.Optional;

public interface ConversationRequestStore {
    Optional<ConversationRequest> find(String workspaceId, String bundleId);
    void create(ConversationRequest request);
}
