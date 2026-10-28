package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.ConversationRequest;
import java.util.Optional;

public interface ConversationRequestStore {
    Optional<ConversationRequest> find(String workspaceId, String bundleId);
    void create(ConversationRequest request);
}
