package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.GenerationJobStore;
import dev.knowledgeforge.knowledge.domain.GenerationJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGenerationJobStore implements GenerationJobStore {
    private final JdbcTemplate jdbc;
    private final RowMapper<GenerationJob> mapper = (row, number) -> new GenerationJob(row.getString("id"),
            row.getString("workspace_id"), row.getString("source_card_id"), row.getString("provider"),
            row.getInt("expected_version"), row.getString("digest"), row.getString("status"),
            row.getString("result_card_id"), row.getString("error"), row.getString("created_at"), row.getString("updated_at"));

    public JdbcGenerationJobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<GenerationJob> find(String workspace, String id) {
        return jdbc.query("SELECT * FROM generation_job WHERE workspace_id=? AND id=?", mapper, workspace, id).stream().findFirst();
    }

    public List<GenerationJob> list(String workspace) {
        return jdbc.query("SELECT * FROM generation_job WHERE workspace_id=? ORDER BY created_at DESC,id DESC LIMIT 20", mapper, workspace);
    }

    public void create(GenerationJob job) {
        jdbc.update("""
                INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, job.id(), job.workspaceId(), job.sourceCardId(), job.provider(), job.expectedVersion(), job.digest(),
                job.status(), job.createdAt(), job.updatedAt());
    }

    public boolean update(String workspace, String id, String status, String resultCardId, String error) {
        return jdbc.update("""
                UPDATE generation_job SET status=?,result_card_id=?,error=?,updated_at=?
                WHERE workspace_id=? AND id=? AND status IN ('QUEUED','RUNNING','CANCELLING')
                """, status, resultCardId, error, Instant.now().toString(), workspace, id) == 1;
    }
}
