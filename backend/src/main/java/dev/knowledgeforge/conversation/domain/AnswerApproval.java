package dev.knowledgeforge.conversation.domain;

public record AnswerApproval(String id, String bundleId, String provider, String requestedModel, String digest,
                             String approvedAt, String expiresAt, String revokedAt) {}
