package dev.dougle.conversation.adapter;

import dev.dougle.conversation.application.AnswerJobStore;
import dev.dougle.conversation.domain.AnswerJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnswerJobStore implements AnswerJobStore {
    private final JdbcTemplate jdbc;
    private final RowMapper<AnswerJob> mapper = (row, index) -> new AnswerJob(row.getString("id"), row.getString("workspace_id"),
            row.getString("bundle_id"), row.getString("approval_id"), row.getString("provider"), row.getString("requested_model"),
            row.getString("digest"), row.getString("status"), row.getString("answer"), row.getString("error"),
            row.getString("created_at"), row.getString("updated_at"));
    public JdbcAnswerJobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<AnswerJob> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM answer_job WHERE workspace_id=? AND id=?", mapper, workspaceId, id).stream().findFirst();
    }
    public Optional<AnswerJob> byApproval(String approvalId) {
        return jdbc.query("SELECT * FROM answer_job WHERE approval_id=?", mapper, approvalId).stream().findFirst();
    }
    public List<AnswerJob> list(String workspaceId) {
        return jdbc.query("SELECT * FROM answer_job WHERE workspace_id=? ORDER BY created_at DESC,id", mapper, workspaceId);
    }
    public void create(AnswerJob job) {
        jdbc.update("INSERT INTO answer_job VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", job.id(), job.workspaceId(), job.bundleId(), job.approvalId(),
                job.provider(), job.requestedModel(), job.digest(), job.status(), job.answer(), job.error(), job.createdAt(), job.updatedAt());
    }
    public boolean update(String workspaceId, String id, String status, String answer, String error) {
        return jdbc.update("UPDATE answer_job SET status=?,answer=?,error=?,updated_at=? WHERE workspace_id=? AND id=? AND status IN ('QUEUED','RUNNING','CANCELLING')",
                status, answer, error, Instant.now().toString(), workspaceId, id) == 1;
    }
}
