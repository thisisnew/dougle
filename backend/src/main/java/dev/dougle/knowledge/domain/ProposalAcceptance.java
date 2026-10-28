package dev.dougle.knowledge.domain;

public record ProposalAcceptance(int sourceVersion, int resultVersion, int appliedVersion,
                                 int decisionRevision, String note, String acceptedAt, String mode) {}
