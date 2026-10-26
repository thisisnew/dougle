package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.BrowserCaptureScope;
import dev.knowledgeforge.investigation.application.BrowserCaptureTransport;
import dev.knowledgeforge.investigation.application.BrowserInvestigationStore;
import dev.knowledgeforge.library.application.LibraryException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcBrowserInvestigationStore implements BrowserInvestigationStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final RowMapper<Job> jobs = (row, index) -> new Job(row.getString("request_id"), row.getString("workspace_id"),
            row.getString("bundle_id"), row.getString("digest"), row.getString("status"),
            row.getString("result_json") == null ? null : json.readValue(row.getString("result_json"), BrowserCaptureTransport.Snapshot.class),
            row.getString("error"), row.getString("approved_at"), row.getString("updated_at"));

    public JdbcBrowserInvestigationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        if (available()) jdbc.update("""
                UPDATE browser_investigation SET status='FAILED',result_json=NULL,error='브라우저 조사가 중단되었습니다. 범위를 다시 확인하고 동의해 주세요.',updated_at=?
                WHERE status IN ('QUEUED','RUNNING','CANCELLING')
                """, Instant.now().toString());
    }

    public boolean available() {
        return Integer.valueOf(1).equals(jdbc.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='browser_investigation'", Integer.class));
    }

    public void save(Preview preview) {
        jdbc.update("DELETE FROM browser_investigation WHERE status='PREVIEW' AND expires_at<=?", Instant.now().toString());
        Integer count = jdbc.queryForObject("SELECT count(*) FROM browser_investigation WHERE workspace_id=?", Integer.class, preview.workspaceId());
        if (count != null && count >= 100) throw new LibraryException(409, "브라우저 조사 보관 한도 100개입니다. 불필요한 질문 기록을 삭제해 주세요.");
        jdbc.update("""
                INSERT INTO browser_investigation(digest,workspace_id,bundle_id,bundle_digest,scope_json,created_at,expires_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, preview.digest(), preview.workspaceId(), preview.bundleId(), preview.bundleDigest(), json.writeValueAsString(preview.scope()),
                preview.createdAt(), preview.expiresAt(), preview.createdAt());
    }

    public Optional<Preview> preview(String workspace, String digest) {
        return jdbc.query("SELECT * FROM browser_investigation WHERE workspace_id=? AND digest=?", (row, index) ->
                new Preview(digest, workspace, row.getString("bundle_id"), row.getString("bundle_digest"),
                        json.readValue(row.getString("scope_json"), BrowserCaptureScope.class), row.getString("created_at"), row.getString("expires_at")),
                workspace, digest).stream().findFirst();
    }

    public boolean consume(String workspace, String digest, String requestId, String now) {
        return jdbc.update("""
                UPDATE browser_investigation SET request_id=?,approved_at=?,updated_at=?,status='QUEUED'
                WHERE workspace_id=? AND digest=? AND status='PREVIEW' AND request_id IS NULL
                """, requestId, now, now, workspace, digest) == 1;
    }

    public Optional<Job> find(String workspace, String requestId) {
        return jdbc.query("SELECT * FROM browser_investigation WHERE workspace_id=? AND request_id=?", jobs, workspace, requestId).stream().findFirst();
    }

    public List<Job> list(String workspace, String bundleId) {
        return jdbc.query("SELECT * FROM browser_investigation WHERE workspace_id=? AND bundle_id=? AND approved_at IS NOT NULL",
                        jobs, workspace, bundleId).stream().sorted(Comparator.comparing((Job job) -> Instant.parse(job.approvedAt()))
                        .thenComparing(Job::id)).toList();
    }

    public boolean update(String workspace, String requestId, String status, BrowserCaptureTransport.Snapshot result, String error) {
        return jdbc.update("""
                UPDATE browser_investigation SET status=?,result_json=?,error=?,updated_at=?
                WHERE workspace_id=? AND request_id=? AND status IN ('QUEUED','RUNNING','CANCELLING')
                """, status, result == null ? null : json.writeValueAsString(result), error, Instant.now().toString(), workspace, requestId) == 1;
    }
}
