package dev.dougle.knowledge.domain;

public record ProposalDecision(int revision, String status, int sourceVersion, int resultVersion,
                               String note, String createdAt) {}
