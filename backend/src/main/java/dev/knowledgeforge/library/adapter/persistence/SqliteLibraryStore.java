package dev.knowledgeforge.library.adapter.persistence;

import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Entry;
import dev.knowledgeforge.library.domain.Workspace;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SqliteLibraryStore implements LibraryStore {
    private static final RowMapper<Workspace> WORKSPACE = (result, row) -> new Workspace(
            result.getString("id"), result.getString("name"), result.getString("created_at"));
    private static final RowMapper<Entry> ENTRY = (result, row) -> new Entry(
            result.getString("id"), result.getString("workspace_id"), result.getString("parent_id"),
            result.getString("name"), result.getString("kind"), result.getString("area"),
            result.getLong("size"), result.getString("created_at"));
    private static final String ENTRY_COLUMNS = "id,workspace_id,parent_id,name,kind,area,size,created_at";
    private final JdbcTemplate jdbc;

    public SqliteLibraryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Workspace> workspaces() {
        return jdbc.query("SELECT * FROM workspace ORDER BY created_at,id", WORKSPACE);
    }

    public Optional<Workspace> workspace(String id) {
        return jdbc.query("SELECT * FROM workspace WHERE id=?", WORKSPACE, id).stream().findFirst();
    }

    public void createWorkspace(Workspace workspace) {
        jdbc.update("INSERT INTO workspace VALUES (?,?,?)", workspace.id(), workspace.name(), workspace.createdAt());
    }

    public void deleteWorkspace(String id) {
        jdbc.update("DELETE FROM workspace WHERE id=?", id);
    }

    public String activeWorkspace() {
        return jdbc.queryForObject("SELECT active_workspace_id FROM app_state WHERE id=1", String.class);
    }

    public void activate(String id) {
        jdbc.update("UPDATE app_state SET active_workspace_id=? WHERE id=1", id);
    }

    public List<Entry> entries(String workspaceId, String area, String parentId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                 FROM entry WHERE workspace_id=? AND area=? AND parent_id IS ?
                 ORDER BY CASE kind WHEN 'FOLDER' THEN 0 ELSE 1 END,name COLLATE NOCASE,id
                """, ENTRY, workspaceId, area, parentId);
    }

    public Optional<Entry> entry(String workspaceId, String id) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + " FROM entry WHERE workspace_id=? AND id=?",
                ENTRY, workspaceId, id).stream().findFirst();
    }

    public void createEntry(Entry entry, byte[] content) {
        jdbc.update("""
                INSERT INTO entry(id,workspace_id,parent_id,name,kind,area,size,content,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, entry.id(), entry.workspaceId(), entry.parentId(), entry.name(), entry.kind(),
                entry.area(), entry.size(), content, entry.createdAt());
    }

    public byte[] content(String id) {
        return jdbc.queryForObject("SELECT content FROM entry WHERE id=?", byte[].class, id);
    }

    public void deleteEntry(String workspaceId, String id) {
        jdbc.update("DELETE FROM entry WHERE workspace_id=? AND id=?", workspaceId, id);
    }
}
