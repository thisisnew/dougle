package dev.dougle.search.adapter;

import dev.dougle.search.application.RelationCatalog;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRelationCatalog implements RelationCatalog {
    private static final String LATEST = """
            WITH published AS (
              SELECT c.id,c.workspace_id,p.version,v.title FROM knowledge_card c
              JOIN knowledge_publication p ON p.card_id=c.id
              JOIN knowledge_version v ON v.card_id=c.id AND v.number=p.version
              WHERE c.workspace_id=:workspace AND p.revision=(SELECT max(revision) FROM knowledge_publication WHERE card_id=c.id)
            ), references_by_card AS (
              SELECT p.id,p.version,snapshot.origin_key,snapshot.entry_id,snapshot.source_id,snapshot.path
              FROM published p JOIN knowledge_evidence evidence ON evidence.card_id=p.id AND evidence.version=p.version
              JOIN evidence_snapshot snapshot ON snapshot.id=evidence.snapshot_id AND snapshot.workspace_id=:workspace
            )
            """;
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcRelationCatalog(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public boolean exists(String workspaceId, String kind, String id, Integer version) {
        if ("ORIGINAL".equals(kind)) {
            return jdbc.queryForObject("SELECT count(*) FROM entry WHERE workspace_id=:workspace AND id=:id AND kind='FILE'",
                    Map.of("workspace", workspaceId, "id", id), Integer.class) == 1;
        }
        return jdbc.queryForObject(LATEST + " SELECT count(*) FROM published WHERE id=:id AND version=:version",
                Map.of("workspace", workspaceId, "id", id, "version", version), Integer.class) == 1;
    }

    public List<Related> related(String workspaceId, String kind, String id, Integer version, List<String> domains) {
        boolean original = "ORIGINAL".equals(kind);
        String origins = original ? """
                SELECT CASE WHEN source_id IS NULL THEN 'entry:'||id ELSE 'source:'||source_id||':'||relative_path END AS origin_key
                FROM entry WHERE workspace_id=:workspace AND id=:id
                """ : "SELECT origin_key FROM references_by_card WHERE id=:id AND version=:version";
        String originals = original ? """
                SELECT entry.id,entry.name AS title,coalesce(entry.relative_path,entry.name) AS path,
                  coalesce(source.domain,'') AS domain,'SAME_SYMBOL' AS relation,substr(min(candidate.name),1,120) AS detail
                FROM symbol seed JOIN symbol candidate ON candidate.name=seed.name AND candidate.kind=seed.kind
                JOIN entry ON entry.id=candidate.entry_id AND entry.workspace_id=:workspace
                LEFT JOIN source ON source.id=entry.source_id
                WHERE seed.entry_id=:id AND entry.id<>:id
                GROUP BY entry.id
                """ : """
                SELECT DISTINCT entry.id,entry.name AS title,coalesce(entry.relative_path,entry.name) AS path,
                  coalesce(source.domain,'') AS domain,'CITED_SOURCE' AS relation,'현재 로컬 원본; 당시 근거와 다를 수 있음' AS detail
                FROM references_by_card reference JOIN entry ON entry.workspace_id=:workspace AND entry.kind='FILE'
                  AND ((reference.source_id IS NULL AND reference.entry_id=entry.id)
                    OR (reference.source_id=entry.source_id AND reference.path=entry.relative_path))
                LEFT JOIN source ON source.id=entry.source_id WHERE reference.id=:id AND reference.version=:version
                """;
        String originalFilter = domains.isEmpty() ? "" : " WHERE domain IN (:domains)";
        String knowledgeFilter = domains.isEmpty() ? "" : """
                AND EXISTS (SELECT 1 FROM references_by_card domain_ref JOIN source ON source.id=domain_ref.source_id
                  AND source.workspace_id=:workspace WHERE domain_ref.id=published.id AND source.domain IN (:domains))
                """;
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("workspace", workspaceId).addValue("id", id).addValue("version", version)
                .addValue("domains", domains);
        String query = LATEST + ", origins AS (" + origins + "), originals AS (" + originals + ") "
                + "SELECT 'ORIGINAL' AS kind,id,title,NULL AS version,path,domain,relation,detail FROM originals" + originalFilter
                + " UNION ALL SELECT 'KNOWLEDGE',published.id,published.title,published.version,'','',:relation,'같은 출처 연결' FROM published"
                + " WHERE published.id<>:id AND EXISTS (SELECT 1 FROM references_by_card reference JOIN origins ON origins.origin_key=reference.origin_key WHERE reference.id=published.id)"
                + knowledgeFilter + " ORDER BY kind,id LIMIT 31";
        parameters.addValue("relation", original ? "CITES_SOURCE" : "SHARED_EVIDENCE");
        return jdbc.query(query, parameters, (result, row) -> {
            String targetId = result.getString("id");
            boolean knowledge = "KNOWLEDGE".equals(result.getString("kind"));
            List<String> targetDomains = knowledge ? jdbc.queryForList(LATEST + """
                     SELECT DISTINCT source.domain FROM references_by_card reference
                     JOIN source ON source.id=reference.source_id AND source.workspace_id=:workspace
                     WHERE reference.id=:target AND source.domain<>'' ORDER BY source.domain
                    """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("workspace", workspaceId).addValue("target", targetId), String.class)
                    : result.getString("domain").isEmpty() ? List.of() : List.of(result.getString("domain"));
            return new Related(result.getString("kind"), targetId, result.getString("title"),
                    knowledge ? result.getInt("version") : null, result.getString("path"), targetDomains,
                    result.getString("relation"), result.getString("detail"));
        });
    }
}
