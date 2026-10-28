package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.PendingOperationalScope;

@FunctionalInterface
public interface ConversationOperationalScopeAccess {
    PendingOperationalScope requireCurrent(String workspaceId, String scopeId);
}
