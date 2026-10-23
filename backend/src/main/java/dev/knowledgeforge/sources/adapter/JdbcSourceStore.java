package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.ingestion.application.AnalysisStore;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.sources.application.SnapshotPlanner;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import dev.knowledgeforge.sources.domain.Source;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSourceStore implements SourceStore {
    private final JdbcTemplate jdbc;
    private final AnalysisStore analyses;
    private final java.time.Clock clock;
    private static final RowMapper<Source> SOURCE = (result, row) -> new Source(
            result.getString("id"), result.getString("workspace_id"), result.getString("name"),
            result.getString("kind"), result.getString("location"), result.getString("domain"),
            result.getString("status"), result.getString("revision"), result.getString("error"),
            result.getString("synced_at"), result.getString("root_entry_id"),
            result.getBoolean("polling_enabled"), result.getInt("poll_interval_seconds"),
            result.getObject("next_poll_at") == null ? null : result.getLong("next_poll_at"),
            result.getInt("consecutive_failures"), result.getInt("polling_revision"));

    public JdbcSourceStore(JdbcTemplate jdbc, AnalysisStore analyses, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.analyses = analyses;
        this.clock = clock;
    }

    public List<Source> list(String workspaceId) {
        return jdbc.query("SELECT * FROM source WHERE workspace_id=? ORDER BY name", SOURCE, workspaceId);
    }

    public Optional<Source> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM source WHERE workspace_id=? AND id=?", SOURCE, workspaceId, id)
                .stream().findFirst();
    }

    public void create(Source source) {
        jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,domain) VALUES(?,?,?,?,?,?)",
                source.id(), source.workspaceId(), source.name(), source.kind(), source.location(), source.domain());
    }

    public boolean begin(String workspaceId, String id) {
        return jdbc.update("UPDATE source SET status='SYNCING',error=NULL WHERE workspace_id=? AND id=? AND status!='SYNCING'",
                workspaceId, id) == 1;
    }

    public void fail(String id, String message) {
        jdbc.update("""
                UPDATE source SET status='ERROR',error=?,
                  next_poll_at=CASE WHEN polling_enabled=1 THEN ? + 1000 * min(86400, poll_interval_seconds * (1 << min(consecutive_failures+1,16))) ELSE NULL END,
                  consecutive_failures=min(consecutive_failures+1,16) WHERE id=?
                """, message, clock.millis(), id);
    }

    public List<Source> due(int limit) {
        return jdbc.query("SELECT * FROM source WHERE polling_enabled=1 AND next_poll_at<=? AND status!='SYNCING' ORDER BY next_poll_at,id LIMIT ?",
                SOURCE, clock.millis(), limit);
    }

    public boolean beginPoll(String workspaceId, String id) {
        return jdbc.update("UPDATE source SET status='SYNCING',error=NULL WHERE workspace_id=? AND id=? AND status!='SYNCING' AND polling_enabled=1 AND next_poll_at<=?",
                workspaceId, id, clock.millis()) == 1;
    }

    public boolean configurePolling(String workspaceId, String id, boolean enabled, int intervalSeconds, int expectedRevision) {
        return jdbc.update("""
                UPDATE source SET polling_enabled=?,poll_interval_seconds=?,next_poll_at=?,consecutive_failures=0,polling_revision=polling_revision+1
                WHERE workspace_id=? AND id=? AND polling_revision=?
                """, enabled, intervalSeconds, enabled ? clock.millis() + intervalSeconds * 1000L : null,
                workspaceId, id, expectedRevision) == 1;
    }

    public Map<String, String> fileHashes(String workspaceId, String id) {
        Map<String, String> hashes = new HashMap<>();
        jdbc.query("SELECT relative_path,content_hash FROM entry WHERE workspace_id=? AND source_id=? AND kind='FILE'",
                result -> { hashes.put(result.getString("relative_path"), result.getString("content_hash")); }, workspaceId, id);
        return hashes;
    }

    @Transactional
    public boolean editMetadata(String workspaceId, String id, String name, String domain, int expectedRevision) {
        if (jdbc.update("""
                UPDATE source SET name=?,domain=?,polling_revision=polling_revision+1
                WHERE workspace_id=? AND id=? AND polling_revision=? AND status!='SYNCING'
                """, name, domain, workspaceId, id, expectedRevision) != 1) return false;
        jdbc.update("UPDATE entry SET name=? WHERE workspace_id=? AND id=(SELECT root_entry_id FROM source WHERE id=?)",
                name, workspaceId, id);
        jdbc.update("INSERT INTO search_outbox(entry_id) SELECT id FROM entry WHERE workspace_id=? AND source_id=? AND kind='FILE'",
                workspaceId, id);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<CollectedSnapshot> snapshot(String workspaceId, String id) {
        return find(workspaceId, id).filter(source -> source.revision() != null).map(source -> {
            var files = jdbc.query("SELECT relative_path,content FROM entry WHERE workspace_id=? AND source_id=? AND kind='FILE' ORDER BY relative_path",
                    (result, row) -> new CollectedSnapshot.File(result.getString("relative_path"), result.getBytes("content")), workspaceId, id);
            var validators = jdbc.query("SELECT uri,etag,last_modified FROM source_http_cache WHERE source_id=?",
                    (result, row) -> new dev.knowledgeforge.sources.domain.HttpValidators(result.getString("uri"),
                            result.getString("etag"), result.getString("last_modified")), id).stream().findFirst().orElse(null);
            return new CollectedSnapshot(source.revision(), CollectionLimits.snapshot(files).files(), validators);
        });
    }

    @Transactional
    public void publish(Source source, CollectedSnapshot snapshot, Map<String, Analysis> extracted) {
        Source current = find(source.workspaceId(), source.id()).orElseThrow(() -> new LibraryException(404, "삭제된 Source입니다."));
        if (!"SYNCING".equals(current.status()) || !Objects.equals(source.revision(), current.revision())
                || !Objects.equals(source.rootEntryId(), current.rootEntryId())) {
            throw new LibraryException(409, "Source 동기화 기준이 변경되었습니다.");
        }
        CollectionLimits.snapshot(snapshot.files());
        var plan = SnapshotPlanner.plan(snapshot, fileHashes(source.workspaceId(), source.id()));
        for (var file : plan.changed()) {
            if (extracted.get(file.path()) == null) throw new IllegalStateException("Missing changed file analysis");
        }
        String rootId = current.rootEntryId();
        if (rootId == null) {
            rootId = insert(source, null, source.name(), "", "FOLDER", new byte[0]);
            jdbc.update("UPDATE source SET root_entry_id=? WHERE id=?", rootId, source.id());
        }
        Map<String, String> folders = new HashMap<>();
        Map<String, String> files = new HashMap<>();
        var entries = jdbc.query("SELECT id,relative_path,kind FROM entry WHERE source_id=? AND id!=? ORDER BY length(relative_path) DESC",
                (result, row) -> new StoredEntry(result.getString("id"), result.getString("relative_path"), result.getString("kind")),
                source.id(), rootId);
        for (var entry : entries) {
            boolean folder = "FOLDER".equals(entry.kind());
            if (folder ? plan.folders().contains(entry.path()) : plan.hashes().containsKey(entry.path())) {
                (folder ? folders : files).put(entry.path(), entry.id());
            } else {
                jdbc.update("DELETE FROM entry WHERE id=?", entry.id());
            }
        }
        folders.put("", rootId);
        for (var file : plan.changed()) {
            String[] parts = file.path().split("/");
            String path = "";
            String parentId = rootId;
            for (int index = 0; index < parts.length - 1; index++) {
                path = path.isEmpty() ? parts[index] : path + "/" + parts[index];
                String folderId = folders.get(path);
                if (folderId == null) {
                    folderId = insert(source, parentId, parts[index], path, "FOLDER", new byte[0]);
                    folders.put(path, folderId);
                }
                parentId = folderId;
            }
            String id = files.get(file.path());
            if (id == null) {
                id = insert(source, parentId, parts[parts.length - 1], file.path(), "FILE", file.content());
            } else {
                jdbc.update("UPDATE entry SET size=?,content=?,content_hash=? WHERE id=?",
                        file.content().length, file.content(), plan.hashes().get(file.path()), id);
            }
            analyses.save(id, extracted.get(file.path()));
        }
        jdbc.update("DELETE FROM source_http_cache WHERE source_id=?", source.id());
        if (snapshot.validators() != null && "URL".equals(source.kind())) {
            var validators = snapshot.validators();
            jdbc.update("INSERT INTO source_http_cache(source_id,uri,etag,last_modified) VALUES(?,?,?,?)",
                    source.id(), validators.uri(), validators.etag(), validators.lastModified());
        }
        jdbc.update("""
                UPDATE source SET status='READY',revision=?,error=NULL,synced_at=?,consecutive_failures=0,
                  next_poll_at=CASE WHEN polling_enabled=1 THEN ? + poll_interval_seconds * 1000 ELSE NULL END WHERE id=?
                """, snapshot.revision(), clock.instant().toString(), clock.millis(), source.id());
    }

    private record StoredEntry(String id, String path, String kind) {}

    private String insert(Source source, String parent, String name, String path, String kind, byte[] content) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO entry(id,workspace_id,parent_id,name,kind,area,size,content,created_at,source_id,relative_path,content_hash)
                VALUES(?,?,?,?,?,'ORIGINAL',?,?,?,?,?,?)
                """, id, source.workspaceId(), parent, name, kind, content.length, content, Instant.now().toString(),
                source.id(), path, CollectionLimits.hash(content));
        return id;
    }

    @Transactional
    public void delete(String workspaceId, String id) {
        jdbc.update("DELETE FROM source WHERE workspace_id=? AND id=?", workspaceId, id);
    }
}
