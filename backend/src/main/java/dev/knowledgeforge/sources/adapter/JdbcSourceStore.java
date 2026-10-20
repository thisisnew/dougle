package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.ingestion.application.AnalysisStore;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import dev.knowledgeforge.sources.domain.Source;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSourceStore implements SourceStore {
    private final JdbcTemplate jdbc;
    private final AnalysisStore analyses;
    private static final RowMapper<Source> SOURCE = (result, row) -> new Source(
            result.getString("id"), result.getString("workspace_id"), result.getString("name"),
            result.getString("kind"), result.getString("location"), result.getString("domain"),
            result.getString("status"), result.getString("revision"), result.getString("error"),
            result.getString("synced_at"), result.getString("root_entry_id"),
            result.getBoolean("polling_enabled"), result.getInt("poll_interval_seconds"));

    public JdbcSourceStore(JdbcTemplate jdbc, AnalysisStore analyses) {
        this.jdbc = jdbc;
        this.analyses = analyses;
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
        jdbc.update("UPDATE source SET status='ERROR',error=? WHERE id=?", message, id);
    }

    @Transactional
    public void publish(Source source, CollectedSnapshot snapshot, Map<String, Analysis> extracted) {
        Source current = find(source.workspaceId(), source.id()).orElseThrow(() -> new LibraryException(404, "삭제된 Source입니다."));
        if (snapshot.revision().equals(current.revision()) && current.rootEntryId() != null) {
            jdbc.update("UPDATE source SET status='READY',error=NULL,synced_at=? WHERE id=?", Instant.now().toString(), source.id());
            return;
        }
        String rootId = current.rootEntryId();
        if (rootId == null) {
            rootId = insert(source, null, source.name(), "", "FOLDER", new byte[0]);
            jdbc.update("UPDATE source SET root_entry_id=? WHERE id=?", rootId, source.id());
        }
        jdbc.update("DELETE FROM entry WHERE source_id=? AND id!=?", source.id(), rootId);
        Map<String, String> folders = new HashMap<>();
        folders.put("", rootId);
        for (var file : snapshot.files()) {
            CollectionLimits.path(file.path());
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
            String id = insert(source, parentId, parts[parts.length - 1], file.path(), "FILE", file.content());
            analyses.save(id, extracted.get(file.path()));
        }
        jdbc.update("UPDATE source SET status='READY',revision=?,error=NULL,synced_at=? WHERE id=?",
                snapshot.revision(), Instant.now().toString(), source.id());
    }

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
