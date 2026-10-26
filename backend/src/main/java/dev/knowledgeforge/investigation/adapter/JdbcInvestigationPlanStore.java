package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.InvestigationPlanStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvestigationPlanStore implements InvestigationPlanStore {
    private final JdbcTemplate jdbc;
    private final RowMapper<Job> jobs = (row, index) -> new Job(row.getString("request_id"), row.getString("workspace_id"),
            row.getString("bundle_id"), row.getString("provider"), row.getString("requested_model"), row.getString("digest"),
            row.getString("status"), row.getString("decision"), row.getString("query"), row.getString("output"),
            row.getBoolean("truncated"), row.getString("tool_status"), row.getString("error"), row.getString("approved_at"), row.getString("updated_at"));

    public JdbcInvestigationPlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.update("""
                UPDATE investigation_plan SET status='FAILED', output=NULL, error='검색 계획이 중단되었습니다. 새 미리보기와 동의가 필요합니다.', updated_at=?
                WHERE status IN ('QUEUED','RUNNING','CANCELLING')
                """, Instant.now().toString());
    }

    public void save(Preview preview) {
        jdbc.update("DELETE FROM investigation_plan WHERE status='PREVIEW' AND expires_at<=?", Instant.now().toString());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM investigation_plan WHERE workspace_id=?", Integer.class, preview.workspaceId());
        if (count != null && count >= 100) throw new dev.knowledgeforge.library.application.LibraryException(409, "검색 계획 보관 한도 100개입니다. 불필요한 근거 묶음을 삭제하세요.");
        jdbc.update("""
                INSERT INTO investigation_plan(digest,workspace_id,bundle_id,provider,requested_model,binding_digest,prompt,created_at,expires_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, preview.digest(), preview.workspaceId(), preview.bundleId(), preview.provider(), preview.requestedModel(),
                preview.bindingDigest(), preview.prompt(), preview.createdAt(), preview.expiresAt(), preview.createdAt());
    }

    public Optional<Preview> preview(String workspaceId, String digest) {
        return jdbc.query("SELECT * FROM investigation_plan WHERE workspace_id=? AND digest=?",
                (row, index) -> new Preview(workspaceId, row.getString("bundle_id"), row.getString("provider"), row.getString("requested_model"),
                        digest, row.getString("binding_digest"), row.getString("prompt"), row.getString("created_at"), row.getString("expires_at")),
                workspaceId, digest).stream().findFirst();
    }

    public boolean consume(String workspaceId, String digest, String requestId, String now) {
        return jdbc.update("""
                UPDATE investigation_plan SET request_id=?,approved_at=?,updated_at=?,status='QUEUED'
                WHERE workspace_id=? AND digest=? AND status='PREVIEW' AND approved_at IS NULL
                """, requestId, now, now, workspaceId, digest) == 1;
    }

    public Optional<Job> find(String workspaceId, String requestId) {
        return jdbc.query("SELECT * FROM investigation_plan WHERE workspace_id=? AND request_id=?", jobs, workspaceId, requestId).stream().findFirst();
    }

    public List<Job> list(String workspaceId, String bundleId) {
        return jdbc.query("""
                SELECT * FROM investigation_plan WHERE workspace_id=? AND bundle_id=? AND approved_at IS NOT NULL
                ORDER BY approved_at,request_id
                """, jobs, workspaceId, bundleId).stream()
                .sorted(java.util.Comparator.comparing((Job job) -> Instant.parse(job.approvedAt())).thenComparing(Job::id)).toList();
    }

    public boolean update(String workspaceId, String requestId, String status, String decision, String query,
                          String output, boolean truncated, String toolStatus, String error) {
        return jdbc.update("""
                UPDATE investigation_plan SET status=?,decision=?,query=?,output=?,truncated=?,tool_status=?,error=?,updated_at=?
                WHERE workspace_id=? AND request_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')
                """, status, decision, query, output, truncated, toolStatus, error, Instant.now().toString(), workspaceId, requestId) == 1;
    }
}
