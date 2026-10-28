package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchCatalog;
import dev.dougle.search.application.SearchIndex;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSearchCatalog implements SearchCatalog {
    private static final String PUBLISHED = """
            SELECT c.id,c.workspace_id,v.title,v.markdown,p.version FROM knowledge_card c
            JOIN knowledge_publication p ON p.card_id=c.id
            JOIN knowledge_version v ON v.card_id=c.id AND v.number=p.version
            WHERE p.revision=(SELECT MAX(revision) FROM knowledge_publication WHERE card_id=c.id)
            """;
    private static final String SELECT = """
            SELECT e.id,e.workspace_id,e.name,e.source_id,COALESCE(e.relative_path,e.name) AS path,
            COALESCE(s.domain,'') AS domain,d.text,
            COALESCE((SELECT group_concat(name,' ') FROM symbol WHERE entry_id=e.id),'') AS symbols,
            COALESCE((SELECT group_concat(line,',') FROM symbol WHERE entry_id=e.id),'') AS boundaries
            FROM entry e JOIN extracted_document d ON d.entry_id=e.id LEFT JOIN source s ON s.id=e.source_id
            WHERE d.status IN ('READY','PARTIAL')
            """;
    private SearchIndex.Document document(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new SearchIndex.Document(
            result.getString("id"), result.getString("workspace_id"), result.getString("name"), result.getString("text"),
            result.getString("symbols"), result.getString("source_id"), result.getString("domain"), result.getString("path"),
            "ORIGINAL", null, null, List.of(result.getString("domain")),
            java.util.Arrays.stream(result.getString("boundaries").split(",")).filter(value -> !value.isBlank())
                    .map(Integer::valueOf).distinct().sorted().toList(),
            dev.dougle.ingestion.adapter.JdbcTextParts.document(jdbc, result.getString("id")));
    }
    private final JdbcTemplate jdbc;

    public JdbcSearchCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public void each(Consumer<SearchIndex.Document> consumer) {
        jdbc.query(SELECT, (RowCallbackHandler) result -> consumer.accept(document(result, 0)));
        jdbc.query(PUBLISHED, (RowCallbackHandler) result -> consumer.accept(published(result)));
    }

    public List<Change> changes() {
        return jdbc.query("SELECT sequence,entry_id FROM search_outbox ORDER BY sequence LIMIT 200",
                (result, row) -> new Change(result.getLong("sequence"), result.getString("entry_id")));
    }

    @Transactional(readOnly = true)
    public Optional<SearchIndex.Document> document(String id) {
        if (id.startsWith("knowledge:")) {
            return jdbc.query(PUBLISHED + " AND c.id=?", (result, row) -> published(result),
                    id.substring("knowledge:".length())).stream().findFirst();
        }
        return jdbc.query(SELECT + " AND e.id=?", (RowMapper<SearchIndex.Document>) this::document, id).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public void withDocument(String id, Consumer<SearchIndex.Document> consumer, Runnable missing) {
        document(id).ifPresentOrElse(consumer, missing);
    }

    private SearchIndex.Document published(java.sql.ResultSet result) throws java.sql.SQLException {
        String card = result.getString("id");
        int version = result.getInt("version");
        var domains = jdbc.queryForList("""
                SELECT DISTINCT s.domain FROM knowledge_evidence e
                JOIN evidence_snapshot snapshot ON snapshot.id=e.snapshot_id
                JOIN source s ON s.id=snapshot.source_id AND s.workspace_id=snapshot.workspace_id
                WHERE e.card_id=? AND e.version=? AND s.domain<>'' ORDER BY s.domain
                """, String.class, card, version);
        return new SearchIndex.Document("knowledge:" + card, result.getString("workspace_id"),
                result.getString("title"), result.getString("markdown"), "", "", String.join(", ", domains),
                "", "KNOWLEDGE", card, version, domains);
    }

    public void acknowledge(long sequence) {
        jdbc.update("DELETE FROM search_outbox WHERE sequence<=?", sequence);
    }

    public boolean exists(String workspaceId, SearchIndex.Hit hit) {
        if ("KNOWLEDGE".equals(hit.kind())) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM knowledge_card c JOIN knowledge_publication p ON p.card_id=c.id
                    WHERE c.workspace_id=? AND c.id=? AND p.version=?
                    AND p.revision=(SELECT MAX(revision) FROM knowledge_publication WHERE card_id=c.id)
                    """, Integer.class, workspaceId, hit.cardId(), hit.version()) == 1;
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM entry WHERE workspace_id=? AND id=?",
                Integer.class, workspaceId, hit.entryId()) == 1;
    }
}
