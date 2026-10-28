package dev.dougle.transfer.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceBundle;
import dev.dougle.transfer.application.WorkspaceArchivePersistence;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcWorkspaceArchivePersistence implements WorkspaceArchivePersistence {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcWorkspaceArchivePersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireSupportedHistory(String workspaceId) {
        Integer unsupported = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM knowledge_card WHERE workspace_id=?)
                     + (SELECT count(*) FROM question_attachment WHERE workspace_id=?)
                     + (SELECT count(*) FROM conversation WHERE workspace_id=?)
                     + (SELECT count(*) FROM conversation_request WHERE workspace_id=?)
                """, Integer.class, workspaceId, workspaceId, workspaceId, workspaceId);
        if (unsupported != null && unsupported > 0) throw new LibraryException(409,
                "지식 문서·질문 첨부·이전 형식 보관함·고정 문맥 요청이 있는 Workspace는 이 아카이브 형식으로 이전할 수 없습니다.");
    }

    public EvidenceBundle saveBundle(String workspaceId, String createdAt, EvidenceBundle.Payload payload) {
        String encoded = json.writeValueAsString(payload);
        var bundle = new EvidenceBundle(UUID.randomUUID().toString(), workspaceId, createdAt, ContentDigest.of(encoded), payload);
        jdbc.update("INSERT INTO evidence_bundle(id,workspace_id,question,status,used_characters,payload_json,digest,created_at) VALUES(?,?,?,?,?,?,?,?)",
                bundle.id(), workspaceId, payload.question(), payload.status(), payload.usedCharacters(), encoded, bundle.digest(), createdAt);
        return bundle;
    }
}
