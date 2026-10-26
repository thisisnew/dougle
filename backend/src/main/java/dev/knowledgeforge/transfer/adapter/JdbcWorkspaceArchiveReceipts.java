package dev.knowledgeforge.transfer.adapter;

import dev.knowledgeforge.transfer.application.WorkspaceArchiveReceipts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWorkspaceArchiveReceipts implements WorkspaceArchiveReceipts {
    private final JdbcTemplate jdbc;
    public JdbcWorkspaceArchiveReceipts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean reserve(String requestId, String digest, String previewId) {
        int inserted = jdbc.update("""
                INSERT OR IGNORE INTO workspace_archive_import_receipt(request_id,digest,preview_id)
                SELECT ?,?,? WHERE NOT EXISTS (
                  SELECT 1 FROM workspace_archive_import_receipt WHERE preview_id=? AND request_id<>?
                )
                """, requestId, digest, previewId, previewId, requestId);
        return inserted == 1 || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workspace_archive_import_receipt WHERE request_id=?)", Boolean.class, requestId));
    }
    public Receipt get(String requestId) {
        return jdbc.queryForObject("SELECT digest,preview_id,result_json FROM workspace_archive_import_receipt WHERE request_id=?",
                (row, index) -> new Receipt(row.getString(1), row.getString(2), row.getString(3)), requestId);
    }
    public void complete(String requestId, String resultJson) {
        jdbc.update("UPDATE workspace_archive_import_receipt SET result_json=? WHERE request_id=?", resultJson, requestId);
    }
}
