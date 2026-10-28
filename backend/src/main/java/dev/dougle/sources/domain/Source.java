package dev.dougle.sources.domain;

public record Source(String id, String workspaceId, String name, String kind, String location,
                     String domain, String status, String revision, String error, String syncedAt,
                     String rootEntryId, boolean pollingEnabled, int pollIntervalSeconds,
                     Long nextPollAt, int consecutiveFailures, int pollingRevision, String createdAt) {}
