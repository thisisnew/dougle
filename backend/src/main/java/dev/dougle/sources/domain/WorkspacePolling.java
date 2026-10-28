package dev.dougle.sources.domain;

public record WorkspacePolling(boolean enabled, int revision, boolean mixed, int intervalSeconds) {}
