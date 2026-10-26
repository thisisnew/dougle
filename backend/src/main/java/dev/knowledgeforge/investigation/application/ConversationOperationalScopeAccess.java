package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.PendingOperationalScope;

@FunctionalInterface
public interface ConversationOperationalScopeAccess {
    PendingOperationalScope requireCurrent(String workspaceId, String scopeId);
}
