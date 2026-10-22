package dev.knowledgeforge.conversation.domain;

import java.util.List;

public record ConversationRequest(String id, String workspaceId, String conversationId, int revision, String bundleId,
                                  String provider, String requestedModel, List<History> history, String digest,
                                  String prompt, String createdAt) {
    public record History(String jobId, int position, String bundleId, String bundleDigest, String question, String answer) {}
}
