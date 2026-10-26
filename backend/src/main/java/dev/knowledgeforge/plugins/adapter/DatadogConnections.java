package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DatadogConnections {
    public record Status(boolean configured, DatadogLogsClient.Site site, String revision) {}
    private record Saved(DatadogLogsClient.Config config, String revision) {
        @Override public String toString() { return "Datadog saved connection [redacted]"; }
    }
    private final PluginVault vault;
    private final LibraryStore library;
    private final JsonMapper json = new JsonMapper();

    public DatadogConnections(PluginVault vault, LibraryStore library) { this.vault = vault; this.library = library; }

    public Status status(String workspaceId) {
        Saved saved = saved(workspaceId);
        return saved == null ? new Status(false, null, null) : new Status(true, saved.config().site(), saved.revision());
    }

    public Status save(String workspaceId, DatadogLogsClient.Config config) {
        requireWorkspace(workspaceId);
        java.util.Objects.requireNonNull(config);
        String revision = UUID.randomUUID().toString();
        vault.put(key(workspaceId), json.writeValueAsString(new Saved(config, revision)));
        return new Status(true, config.site(), revision);
    }

    public void disconnect(String workspaceId) {
        requireWorkspace(workspaceId);
        vault.put(key(workspaceId), null);
    }

    public DatadogLogsClient.Config require(String workspaceId, String approvedRevision) {
        Saved saved = saved(workspaceId);
        if (saved == null || !saved.revision().equals(approvedRevision)) {
            throw new LibraryException(409, "Datadog 연결이 변경되었습니다. 새 조회 승인이 필요합니다.");
        }
        return saved.config();
    }

    private Saved saved(String workspaceId) {
        requireWorkspace(workspaceId);
        String value = vault.get(key(workspaceId));
        if (value == null) return null;
        try { return json.readValue(value, Saved.class); }
        catch (RuntimeException failure) { throw new LibraryException(500, "Datadog 연결 정보를 읽지 못했습니다."); }
    }

    private void requireWorkspace(String workspaceId) {
        if (workspaceId == null || library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
    }

    private String key(String workspaceId) { return "datadog.workspace." + workspaceId; }
}
