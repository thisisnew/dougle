package dev.dougle.search.application;

import java.util.Optional;

public interface AnswerContextProvider {
    record Context(String id, String conversationId, int revision, String provider, String requestedModel,
                   String prefix, String digest) {}
    Optional<Context> validated(String workspaceId, String bundleId);
}
