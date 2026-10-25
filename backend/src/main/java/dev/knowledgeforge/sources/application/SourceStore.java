package dev.knowledgeforge.sources.application;

import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import dev.knowledgeforge.sources.domain.Source;
import dev.knowledgeforge.sources.domain.WorkspacePolling;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SourceStore {
    List<Source> list(String workspaceId);
    Optional<Source> find(String workspaceId, String id);
    void create(Source source);
    WorkspacePolling workspacePolling(String workspaceId);
    boolean configureWorkspacePolling(String workspaceId, boolean enabled, Integer intervalSeconds, int expectedRevision);
    boolean begin(String workspaceId, String id);
    List<Source> due(int limit);
    boolean beginPoll(String workspaceId, String id);
    boolean configurePolling(String workspaceId, String id, boolean enabled, int intervalSeconds, int expectedRevision);
    boolean editMetadata(String workspaceId, String id, String name, String domain, int expectedRevision);
    Map<String, String> fileHashes(String workspaceId, String id);
    Optional<CollectedSnapshot> snapshot(String workspaceId, String id);
    void fail(String id, String message);
    void publish(Source source, CollectedSnapshot snapshot, Map<String, Analysis> analyses);
    default Optional<Analysis> checkpoint(String sourceId, String path, String hash) { return Optional.empty(); }
    default boolean persistentCheckpoints() { return false; }
    default void checkpoint(String sourceId, String path, String hash, Analysis analysis) {}
    void delete(String workspaceId, String id);
}
