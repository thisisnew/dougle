package dev.dougle.attachment.adapter;

import dev.dougle.attachment.application.AttachmentStore;
import dev.dougle.attachment.domain.QuestionAttachment;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttachmentStore implements AttachmentStore {
    private final JdbcTemplate jdbc;
    public JdbcAttachmentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<QuestionAttachment> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM question_attachment WHERE workspace_id=? AND id=?", (row, index) ->
                new QuestionAttachment(row.getString("id"), workspaceId, row.getString("bundle_id"), row.getString("name"),
                        row.getString("media_type"), row.getBytes("content"), row.getString("content_hash"),
                        row.getString("extracted_text"), row.getBoolean("truncated"), row.getString("created_at")), workspaceId, id).stream().findFirst();
    }
    public void save(QuestionAttachment item) {
        jdbc.update("INSERT INTO question_attachment(id,workspace_id,name,media_type,content,content_hash,extracted_text,truncated,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                item.id(), item.workspaceId(), item.name(), item.mediaType(), item.content(), item.contentHash(), item.text(), item.truncated(), item.createdAt());
    }
    public boolean bind(String workspaceId, String id, String bundleId) {
        return jdbc.update("UPDATE question_attachment SET bundle_id=? WHERE workspace_id=? AND id=? AND bundle_id IS NULL", bundleId, workspaceId, id) == 1;
    }
    public boolean deleteUnbound(String workspaceId, String id) {
        return jdbc.update("DELETE FROM question_attachment WHERE workspace_id=? AND id=? AND bundle_id IS NULL", workspaceId, id) == 1;
    }
    public long usedBytes(String workspaceId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(length(content)),0) FROM question_attachment WHERE workspace_id=?", Long.class, workspaceId);
    }
    public int pendingCount(String workspaceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM question_attachment WHERE workspace_id=? AND bundle_id IS NULL", Integer.class, workspaceId);
    }
    public void expireUnbound(String before) { jdbc.update("DELETE FROM question_attachment WHERE bundle_id IS NULL AND created_at<?", before); }
}
