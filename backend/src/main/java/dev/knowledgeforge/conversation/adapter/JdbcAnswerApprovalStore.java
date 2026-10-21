package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.AnswerApprovalStore;
import dev.knowledgeforge.conversation.domain.AnswerApproval;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnswerApprovalStore implements AnswerApprovalStore {
    private final JdbcTemplate jdbc;
    public JdbcAnswerApprovalStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AnswerApproval> find(String bundleId) {
        return jdbc.query("SELECT * FROM answer_approval WHERE bundle_id=?", (row, index) -> new AnswerApproval(
                row.getString("id"), bundleId, row.getString("provider"), row.getString("requested_model"),
                row.getString("digest"), row.getString("approved_at"), row.getString("expires_at"), row.getString("revoked_at")),
                bundleId).stream().findFirst();
    }

    public void save(AnswerApproval value) {
        jdbc.update("""
                INSERT INTO answer_approval(bundle_id,id,provider,requested_model,digest,approved_at,expires_at,revoked_at)
                VALUES(?,?,?,?,?,?,?,NULL) ON CONFLICT(bundle_id) DO UPDATE SET
                id=excluded.id,provider=excluded.provider,requested_model=excluded.requested_model,digest=excluded.digest,
                approved_at=excluded.approved_at,expires_at=excluded.expires_at,revoked_at=NULL
                """, value.bundleId(), value.id(), value.provider(), value.requestedModel(), value.digest(), value.approvedAt(), value.expiresAt());
    }

    public void revoke(String bundleId, String id, String revokedAt) {
        jdbc.update("UPDATE answer_approval SET revoked_at=COALESCE(revoked_at,?) WHERE bundle_id=? AND id=?", revokedAt, bundleId, id);
    }
}
