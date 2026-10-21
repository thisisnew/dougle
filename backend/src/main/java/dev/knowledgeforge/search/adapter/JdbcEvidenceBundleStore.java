package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.EvidenceBundleStore;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcEvidenceBundleStore implements EvidenceBundleStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcEvidenceBundleStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public EvidenceBundle save(String workspaceId, EvidenceBundle.Payload payload) {
        String encoded = json.writeValueAsString(payload);
        var bundle = new EvidenceBundle(UUID.randomUUID().toString(), workspaceId, Instant.now().toString(), ContentDigest.of(encoded), payload);
        jdbc.update("INSERT INTO evidence_bundle(id,workspace_id,question,status,used_characters,payload_json,digest,created_at) VALUES(?,?,?,?,?,?,?,?)",
                bundle.id(), workspaceId, payload.question(), payload.status(), payload.usedCharacters(), encoded, bundle.digest(), bundle.createdAt());
        return bundle;
    }

    public Optional<EvidenceBundle> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM evidence_bundle WHERE workspace_id=? AND id=?", (row, index) -> {
            String encoded = row.getString("payload_json");
            if (!ContentDigest.of(encoded).equals(row.getString("digest"))) throw new IllegalStateException("Evidence bundle integrity check failed");
            return new EvidenceBundle(row.getString("id"), workspaceId, row.getString("created_at"), row.getString("digest"),
                    json.readValue(encoded, EvidenceBundle.Payload.class));
        }, workspaceId, id).stream().findFirst();
    }

    public List<EvidenceBundle.Summary> list(String workspaceId) {
        return jdbc.query("SELECT id,question,status,used_characters,created_at FROM evidence_bundle WHERE workspace_id=? ORDER BY created_at DESC,id",
                (row, index) -> new EvidenceBundle.Summary(row.getString("id"), row.getString("question"), row.getString("status"),
                        row.getInt("used_characters"), row.getString("created_at")), workspaceId);
    }

    public void delete(String workspaceId, String id) { jdbc.update("DELETE FROM evidence_bundle WHERE workspace_id=? AND id=?", workspaceId, id); }
}
