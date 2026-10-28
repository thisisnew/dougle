package dev.dougle.sources.adapter;

import dev.dougle.ingestion.application.AnalysisStore;
import dev.dougle.ingestion.domain.Analysis;
import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.application.SourceStore;
import dev.dougle.sources.application.SnapshotPlanner;
import dev.dougle.sources.domain.CollectedSnapshot;
import dev.dougle.sources.domain.Source;
import dev.dougle.sources.domain.WorkspacePolling;
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
            result.getInt("consecutive_failures"), result.getInt("polling_revision"), result.getString("created_at"));

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
        jdbc.update("""
                INSERT INTO source(id,workspace_id,name,kind,location,domain,polling_enabled,poll_interval_seconds,next_poll_at,created_at)
                SELECT ?,?,?,?,?,?,COALESCE(policy.enabled,0),
                  COALESCE(policy.interval_seconds,3600),
                  CASE WHEN policy.enabled=1 THEN ?+policy.interval_seconds*1000 ELSE NULL END,?
                FROM workspace LEFT JOIN workspace_source_polling policy ON policy.workspace_id=workspace.id
                WHERE workspace.id=?
                """, source.id(), source.workspaceId(), source.name(), source.kind(), source.location(), source.domain(),
                clock.millis(), clock.instant().toString(), source.workspaceId());
    }

    public WorkspacePolling workspacePolling(String workspaceId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(policy.enabled,0) AS enabled,COALESCE(policy.revision,0) AS revision,
                  COALESCE(policy.interval_seconds,3600) AS interval_seconds,
                  EXISTS(SELECT 1 FROM source WHERE source.workspace_id=workspace.id
                    AND source.polling_enabled!=COALESCE(policy.enabled,0)) AS mixed
                FROM workspace LEFT JOIN workspace_source_polling policy ON policy.workspace_id=workspace.id
                WHERE workspace.id=?
                """, (result, row) -> new WorkspacePolling(result.getBoolean("enabled"),
                result.getInt("revision"), result.getBoolean("mixed"), result.getInt("interval_seconds")), workspaceId);
    }

    @Transactional
    public boolean configureWorkspacePolling(String workspaceId, boolean enabled, Integer intervalSeconds, int expectedRevision) {
        jdbc.update("INSERT OR IGNORE INTO workspace_source_polling(workspace_id,enabled,revision) VALUES(?,0,0)", workspaceId);
        if (jdbc.update("UPDATE workspace_source_polling SET enabled=?,interval_seconds=COALESCE(?,interval_seconds),revision=revision+1 WHERE workspace_id=? AND revision=?",
                enabled, intervalSeconds, workspaceId, expectedRevision) != 1) return false;
        jdbc.update("""
                UPDATE source SET polling_enabled=?,poll_interval_seconds=COALESCE(?,poll_interval_seconds),
                  next_poll_at=CASE WHEN ? THEN ?+COALESCE(?,poll_interval_seconds)*1000 ELSE NULL END,
                  consecutive_failures=0,polling_revision=polling_revision+1 WHERE workspace_id=?
                """, enabled, intervalSeconds, enabled, clock.millis(), intervalSeconds, workspaceId);
        return true;
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
        return jdbc.query("SELECT * FROM source WHERE kind IN ('LOCAL_FOLDER','MYSQL','URL') AND polling_enabled=1 AND next_poll_at<=? AND status!='SYNCING' ORDER BY next_poll_at,id LIMIT ?",
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
        jdbc.query("""
                SELECT e.relative_path,CASE WHEN d.error LIKE '%앞부분만 검색%' THEN NULL ELSE e.content_hash END AS content_hash
                FROM entry e LEFT JOIN extracted_document d ON d.entry_id=e.id
                WHERE e.workspace_id=? AND e.source_id=? AND e.kind='FILE'
                """,
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
            var files = new java.util.ArrayList<CollectedSnapshot.File>();
            try {
            jdbc.query("SELECT relative_path,content FROM entry WHERE workspace_id=? AND source_id=? AND kind='FILE' ORDER BY relative_path",
                    (org.springframework.jdbc.core.RowCallbackHandler) result -> {
                        try (var input = result.getBinaryStream("content")) {
                            files.add(CollectedSnapshot.File.copy(result.getString("relative_path"), input, CollectionLimits.MAX_DOCUMENT));
                        } catch (java.io.IOException exception) {
                            throw new java.sql.SQLException("Cannot stage stored document", exception);
                        }
                    }, workspaceId, id);
            var validators = jdbc.query("SELECT uri,etag,last_modified FROM source_http_cache WHERE source_id=?",
                    (result, row) -> new dev.dougle.sources.domain.HttpValidators(result.getString("uri"),
                            result.getString("etag"), result.getString("last_modified")), id).stream().findFirst().orElse(null);
            Map<String, String> failures = new HashMap<>();
            jdbc.query("SELECT path,reason FROM source_file_failure WHERE source_id=?",
                    result -> { failures.put(result.getString("path"), result.getString("reason")); }, id);
            return new CollectedSnapshot(source.revision(), CollectionLimits.snapshot(files).files(), validators, failures);
            } catch (RuntimeException exception) {
                files.forEach(CollectedSnapshot.File::close);
                throw exception;
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<Analysis> checkpoint(String sourceId, String path, String hash) {
        return jdbc.query("SELECT analysis_json FROM source_analysis_checkpoint WHERE source_id=? AND path=? AND content_hash=?",
                (result, row) -> tools.jackson.databind.json.JsonMapper.builder().build()
                        .readValue(result.getString(1), Analysis.class), sourceId, path, hash).stream().findFirst()
                .map(metadata -> new Analysis(metadata.text(), metadata.mediaType(), metadata.status(), metadata.error(),
                        metadata.symbols(), dev.dougle.ingestion.adapter.JdbcTextParts.checkpoint(jdbc, sourceId, path)));
    }

    public boolean persistentCheckpoints() { return true; }

    @Transactional
    public void checkpoint(String sourceId, String path, String hash, Analysis analysis) {
        try (analysis) {
            jdbc.update("""
                    INSERT INTO source_analysis_checkpoint(source_id,path,content_hash,analysis_json) VALUES(?,?,?,?)
                    ON CONFLICT(source_id,path) DO UPDATE SET content_hash=excluded.content_hash,analysis_json=excluded.analysis_json
                    """, sourceId, path, hash, tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(analysis));
            jdbc.update("DELETE FROM source_analysis_text_part WHERE source_id=? AND path=?", sourceId, path);
            for (int ordinal = 0; ordinal < analysis.parts().size(); ordinal++) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                jdbc.update("INSERT INTO source_analysis_text_part(source_id,path,ordinal,text) VALUES(?,?,?,?)",
                        sourceId, path, ordinal, analysis.parts().get(ordinal));
            }
        }
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
            boolean preserve = snapshot.failures().keySet().stream().anyMatch(path -> path.equals(entry.path())
                    || entry.path().startsWith(path + "/")
                    || folder && path.startsWith(entry.path() + "/"));
            if (preserve || (folder ? plan.folders().contains(entry.path()) : plan.hashes().containsKey(entry.path()))) {
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
            byte[] content = file.content();
            if (id == null) {
                id = insert(source, parentId, parts[parts.length - 1], file.path(), "FILE", content);
            } else {
                jdbc.update("UPDATE entry SET size=?,content=?,content_hash=? WHERE id=?",
                        file.size(), content, plan.hashes().get(file.path()), id);
            }
            analyses.save(id, extracted.get(file.path()));
        }
        jdbc.update("DELETE FROM source_http_cache WHERE source_id=?", source.id());
        if (snapshot.validators() != null && "URL".equals(source.kind())) {
            var validators = snapshot.validators();
            jdbc.update("INSERT INTO source_http_cache(source_id,uri,etag,last_modified) VALUES(?,?,?,?)",
                    source.id(), validators.uri(), validators.etag(), validators.lastModified());
        }
        Map<String, String> failures = new HashMap<>(snapshot.failures());
        jdbc.query("""
                SELECT e.relative_path,d.error FROM entry e JOIN extracted_document d ON d.entry_id=e.id
                WHERE e.source_id=? AND d.status IN ('ERROR','UNSUPPORTED','PARTIAL')
                """, result -> { failures.putIfAbsent(result.getString("relative_path"),
                        Objects.requireNonNullElse(result.getString("error"), "텍스트를 읽을 수 없습니다.")); }, source.id());
        jdbc.update("DELETE FROM source_file_failure WHERE source_id=?", source.id());
        failures.forEach((path, reason) -> jdbc.update(
                "INSERT INTO source_file_failure(source_id,path,reason) VALUES(?,?,?)", source.id(), path, reason));
        String failureSummary = failures.isEmpty() ? null : failures.size() + "개 파일 확인이 필요합니다. "
                + failures.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(3)
                .map(item -> item.getKey() + ": " + item.getValue()).collect(java.util.stream.Collectors.joining(" · "));
        int searchable = jdbc.queryForObject("""
                SELECT count(*) FROM entry e JOIN extracted_document d ON d.entry_id=e.id
                WHERE e.source_id=? AND d.status IN ('READY','PARTIAL')
                AND (length(trim(d.text))>0 OR EXISTS (
                    SELECT 1 FROM extracted_text_part part
                    WHERE part.entry_id=e.id AND length(trim(part.text))>0
                ))
                """, Integer.class, source.id());
        if (!failures.isEmpty() && searchable == 0) {
            fail(source.id(), "검색 가능한 자료가 없습니다. " + failureSummary);
            return;
        }
        jdbc.update("""
                UPDATE source SET status=?,revision=?,error=?,synced_at=?,consecutive_failures=0,
                  next_poll_at=CASE WHEN polling_enabled=1 THEN ? + poll_interval_seconds * 1000 ELSE NULL END WHERE id=?
                """, failures.isEmpty() ? "READY" : "PARTIAL", snapshot.revision(),
                failureSummary,
                clock.instant().toString(), clock.millis(), source.id());
        for (var file : snapshot.files()) {
            jdbc.update("DELETE FROM source_analysis_checkpoint WHERE source_id=? AND path=?", source.id(), file.path());
        }
        for (String path : jdbc.queryForList("SELECT path FROM source_analysis_checkpoint WHERE source_id=?", String.class, source.id())) {
            if (!failures.containsKey(path)) jdbc.update("DELETE FROM source_analysis_checkpoint WHERE source_id=? AND path=?", source.id(), path);
        }
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
