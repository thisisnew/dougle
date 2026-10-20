package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.SearchCatalog;
import dev.knowledgeforge.search.application.SearchIndex;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSearchCatalog implements SearchCatalog {
    private static final String SELECT = """
            SELECT e.id,e.workspace_id,e.name,e.source_id,COALESCE(e.relative_path,e.name) AS path,
            COALESCE(s.domain,'') AS domain,d.text,
            COALESCE((SELECT group_concat(name,' ') FROM symbol WHERE entry_id=e.id),'') AS symbols
            FROM entry e JOIN extracted_document d ON d.entry_id=e.id LEFT JOIN source s ON s.id=e.source_id
            WHERE d.status IN ('READY','PARTIAL')
            """;
    private static final RowMapper<SearchIndex.Document> DOCUMENT = (result, row) -> new SearchIndex.Document(
            result.getString("id"), result.getString("workspace_id"), result.getString("name"), result.getString("text"),
            result.getString("symbols"), result.getString("source_id"), result.getString("domain"), result.getString("path"));
    private final JdbcTemplate jdbc;

    public JdbcSearchCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void each(Consumer<SearchIndex.Document> consumer) {
        jdbc.query(SELECT, (RowCallbackHandler) result -> consumer.accept(DOCUMENT.mapRow(result, 0)));
    }

    public List<Change> changes() {
        return jdbc.query("SELECT sequence,entry_id FROM search_outbox ORDER BY sequence LIMIT 200",
                (result, row) -> new Change(result.getLong("sequence"), result.getString("entry_id")));
    }

    public Optional<SearchIndex.Document> document(String id) {
        return jdbc.query(SELECT + " AND e.id=?", DOCUMENT, id).stream().findFirst();
    }

    public void acknowledge(long sequence) {
        jdbc.update("DELETE FROM search_outbox WHERE sequence<=?", sequence);
    }

    public boolean exists(String workspaceId, String entryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM entry WHERE workspace_id=? AND id=?",
                Integer.class, workspaceId, entryId) == 1;
    }
}
