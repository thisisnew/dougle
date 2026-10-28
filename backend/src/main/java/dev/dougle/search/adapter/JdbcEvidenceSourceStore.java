package dev.dougle.search.adapter;

import dev.dougle.ingestion.adapter.JdbcTextParts;
import dev.dougle.search.application.EvidenceSourceStore;
import dev.dougle.search.domain.EvidenceMaterial;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JdbcEvidenceSourceStore implements EvidenceSourceStore {
    private static final String SELECT = """
            SELECT e.id,e.source_id,s.revision,e.relative_path,e.name,length(e.content) AS content_length,
            COALESCE(d.text,'') AS text,COALESCE(d.status,'PENDING') AS analysis_status
            FROM entry e LEFT JOIN source s ON s.id=e.source_id LEFT JOIN extracted_document d ON d.entry_id=e.id
            WHERE e.workspace_id=? AND e.kind='FILE' AND e.area='ORIGINAL'
            """;
    private final JdbcTemplate jdbc;

    public JdbcEvidenceSourceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Optional<EvidenceMaterial> material(String workspaceId, String entryId) {
        return read(SELECT + " AND e.id=?", workspaceId, entryId);
    }

    @Transactional(readOnly = true)
    public Optional<EvidenceMaterial> currentMaterial(String workspaceId, String entryId, String sourceId, String path) {
        return sourceId == null ? material(workspaceId, entryId)
                : read(SELECT + " AND e.source_id=? AND e.relative_path=?", workspaceId, sourceId, path);
    }

    @Override
    @Transactional(readOnly = true)
    public <T> T withMaterial(String workspaceId, String entryId,
                              java.util.function.Function<Optional<EvidenceMaterial>, T> reader) {
        return reader.apply(material(workspaceId, entryId));
    }

    private Optional<EvidenceMaterial> read(String sql, Object... parameters) {
        return jdbc.query(sql, (result, row) -> {
            String entryId = result.getString("id");
            return new EvidenceMaterial(entryId, result.getString("source_id"), result.getString("revision"),
                    result.getString("relative_path"), result.getString("name"), result.getString("analysis_status"),
                    contentHash(entryId, result.getLong("content_length")), result.getString("text"),
                    JdbcTextParts.document(jdbc, entryId));
        }, parameters).stream().findFirst();
    }

    private String contentHash(String entryId, long length) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            int block = 8 * 1024 * 1024;
            for (long offset = 0; offset < length; offset += block) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                byte[] bytes = jdbc.queryForObject("SELECT substr(content,?,?) FROM entry WHERE id=?",
                        byte[].class, offset + 1, block, entryId);
                if (bytes == null || bytes.length != Math.min(block, length - offset)) {
                    throw new IllegalStateException("Evidence source changed during capture");
                }
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
