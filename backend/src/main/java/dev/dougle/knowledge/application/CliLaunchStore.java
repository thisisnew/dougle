package dev.dougle.knowledge.application;

public interface CliLaunchStore {
    record Configuration(String codexPath, String claudePath, int revision) {}
    Configuration current();
    boolean replace(String codexPath, String claudePath, int expectedRevision);
}
