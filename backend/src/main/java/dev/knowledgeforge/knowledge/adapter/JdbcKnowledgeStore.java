package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class JdbcKnowledgeStore implements KnowledgeStore {
    private final JdbcTemplate jdbc;
    private static final String CARDS = """
            SELECT c.*,v.title FROM knowledge_card c JOIN knowledge_version v
            ON v.card_id=c.id AND v.number=c.latest_version WHERE c.workspace_id=?
            """;
    private static final RowMapper<Card> CARD = (result, row) -> new Card(result.getString("id"),
            result.getString("workspace_id"), result.getString("title"), result.getInt("latest_version"), result.getString("created_at"));
    private static final String MATERIAL = """
            SELECT e.id,e.source_id,s.name AS source_name,s.kind AS source_kind,s.location AS source_location,s.revision,e.relative_path,e.name,e.content,
            COALESCE(d.text,'') AS text,COALESCE(d.status,'PENDING') AS analysis_status
            FROM entry e LEFT JOIN source s ON s.id=e.source_id LEFT JOIN extracted_document d ON d.entry_id=e.id
            WHERE e.workspace_id=? AND e.kind='FILE' AND e.area='ORIGINAL'
            """;
    private static final RowMapper<Material> MATERIAL_ROW = (result, row) -> new Material(result.getString("id"),
            result.getString("source_id"), result.getString("source_name"), result.getString("source_kind"), result.getString("source_location"), result.getString("revision"),
            result.getString("relative_path"), result.getString("name"), result.getBytes("content"),
            result.getString("text"), result.getString("analysis_status"));

    public JdbcKnowledgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Card> list(String workspaceId) {
        return jdbc.query(CARDS + " ORDER BY c.created_at DESC,c.id", CARD, workspaceId);
    }

    public Optional<Card> card(String workspaceId, String id) {
        return jdbc.query(CARDS + " AND c.id=?", CARD, workspaceId, id).stream().findFirst();
    }

    public Optional<Version> version(String workspaceId, String id, int number) {
        return jdbc.query("""
                SELECT v.* FROM knowledge_version v JOIN knowledge_card c ON c.id=v.card_id
                WHERE c.workspace_id=? AND c.id=? AND v.number=?
                """, (result, row) -> new Version(result.getInt("number"), result.getString("title"),
                result.getString("markdown"), result.getString("status"), result.getBoolean("manually_edited"),
                result.getString("created_at"), evidence(workspaceId, id, number)), workspaceId, id, number).stream().findFirst();
    }

    private List<Evidence> evidence(String workspaceId, String id, int number) {
        return jdbc.query("""
                SELECT s.*,e.start_line,e.end_line FROM knowledge_evidence e JOIN evidence_snapshot s ON s.id=e.snapshot_id
                WHERE s.workspace_id=? AND e.card_id=? AND e.version=? ORDER BY e.ordinal
                """, (result, row) -> {
                    String entryId = result.getString("entry_id");
                    String sourceId = result.getString("source_id");
                    String path = result.getString("path");
                    String contentHash = result.getString("content_hash");
                    String textHash = result.getString("text_hash");
                    Optional<Material> current = currentMaterial(workspaceId, entryId, sourceId, path);
                    String freshness = current.map(value -> contentHash.equals(hash(value.content()))
                            && textHash.equals(hash(value.text().getBytes(StandardCharsets.UTF_8))) ? "CURRENT" : "STALE").orElse("MISSING");
                    int start = result.getInt("start_line");
                    int end = result.getInt("end_line");
                    String quote = String.join("\n", Arrays.copyOfRange(result.getString("extracted_text").split("\n", -1), start - 1, end));
                    return new Evidence(result.getString("id"), entryId, sourceId, result.getString("source_name"),
                            result.getString("source_kind"), result.getString("source_location"),
                            result.getString("source_revision"), path, result.getString("name"), contentHash,
                            start, end, quote, freshness, result.getString("captured_at"));
                }, workspaceId, id, number);
    }

    public Optional<Material> material(String workspaceId, String entryId) {
        return jdbc.query(MATERIAL + " AND e.id=?", MATERIAL_ROW, workspaceId, entryId).stream().findFirst();
    }

    public Optional<Material> currentMaterial(String workspaceId, String entryId, String sourceId, String path) {
        return sourceId == null ? material(workspaceId, entryId)
                : jdbc.query(MATERIAL + " AND e.source_id=? AND e.relative_path=?", MATERIAL_ROW,
                        workspaceId, sourceId, path).stream().findFirst();
    }

    public String capture(String workspaceId, Material material) {
        String contentHash = hash(material.content());
        String textHash = hash(material.text().getBytes(StandardCharsets.UTF_8));
        String origin = material.sourceId() == null ? "entry:" + material.entryId() : "source:" + material.sourceId() + ":" + material.path();
        String revision = material.revision() == null ? contentHash : material.revision();
        jdbc.update("""
                INSERT INTO evidence_snapshot(id,workspace_id,origin_key,entry_id,source_id,source_name,source_kind,source_location,source_revision,
                path,name,content_hash,text_hash,content,extracted_text,captured_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(workspace_id,origin_key,source_revision,content_hash,text_hash) DO NOTHING
                """, UUID.randomUUID().toString(), workspaceId, origin, material.entryId(), material.sourceId(), material.sourceName(),
                material.sourceKind(), material.sourceLocation(), revision, material.path() == null ? material.name() : material.path(), material.name(), contentHash, textHash,
                material.content(), material.text(), Instant.now().toString());
        return jdbc.queryForObject("""
                SELECT id FROM evidence_snapshot WHERE workspace_id=? AND origin_key=? AND source_revision=?
                AND content_hash=? AND text_hash=?
                """, String.class, workspaceId, origin, revision, contentHash, textHash);
    }

    public void create(String workspaceId, String id, String createdAt) {
        jdbc.update("INSERT INTO knowledge_card(id,workspace_id,latest_version,created_at) VALUES(?,?,1,?)", id, workspaceId, createdAt);
    }

    public boolean advance(String workspaceId, String id, int expectedVersion) {
        return jdbc.update("UPDATE knowledge_card SET latest_version=latest_version+1 WHERE workspace_id=? AND id=? AND latest_version=?",
                workspaceId, id, expectedVersion) == 1;
    }

    public void append(String id, int number, String title, String markdown, String createdAt, List<Reference> references, boolean manuallyEdited) {
        jdbc.update("INSERT INTO knowledge_version(card_id,number,title,markdown,status,manually_edited,created_at) VALUES(?,?,?,?,'DRAFT',?,?)",
                id, number, title, markdown, manuallyEdited, createdAt);
        for (int index = 0; index < references.size(); index++) {
            Reference reference = references.get(index);
            jdbc.update("INSERT INTO knowledge_evidence(card_id,version,ordinal,snapshot_id,start_line,end_line) VALUES(?,?,?,?,?,?)",
                    id, number, index, reference.snapshotId(), reference.startLine(), reference.endLine());
        }
    }

    public void delete(String workspaceId, String id) {
        jdbc.update("DELETE FROM knowledge_card WHERE workspace_id=? AND id=?", workspaceId, id);
        jdbc.update("""
                DELETE FROM evidence_snapshot WHERE workspace_id=?
                AND NOT EXISTS(SELECT 1 FROM knowledge_evidence e WHERE e.snapshot_id=evidence_snapshot.id)
                """, workspaceId);
    }

    public Optional<byte[]> snapshotContent(String workspaceId, String cardId, String snapshotId) {
        return jdbc.query("""
                SELECT s.content FROM evidence_snapshot s WHERE s.workspace_id=? AND s.id=?
                AND EXISTS(SELECT 1 FROM knowledge_evidence e JOIN knowledge_card c ON c.id=e.card_id
                WHERE e.snapshot_id=s.id AND c.id=? AND c.workspace_id=s.workspace_id)
                """, (result, row) -> result.getBytes("content"), workspaceId, snapshotId, cardId).stream().findFirst();
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
