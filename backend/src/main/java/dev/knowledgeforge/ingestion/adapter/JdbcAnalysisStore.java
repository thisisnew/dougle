package dev.knowledgeforge.ingestion.adapter;

import dev.knowledgeforge.ingestion.application.AnalysisStore;
import dev.knowledgeforge.ingestion.domain.Analysis;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAnalysisStore implements AnalysisStore {
    private final JdbcTemplate jdbc;

    public JdbcAnalysisStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(String entryId, Analysis analysis) {
        jdbc.update("""
                INSERT INTO extracted_document(entry_id,text,media_type,status,error) VALUES(?,?,?,?,?)
                ON CONFLICT(entry_id) DO UPDATE SET text=excluded.text,media_type=excluded.media_type,
                status=excluded.status,error=excluded.error
                """, entryId, analysis.text(), analysis.mediaType(), analysis.status(), analysis.error());
        jdbc.update("DELETE FROM symbol WHERE entry_id=?", entryId);
        for (Analysis.Symbol symbol : analysis.symbols()) {
            jdbc.update("INSERT INTO symbol(entry_id,name,kind,line,signature) VALUES(?,?,?,?,?)",
                    entryId, symbol.name(), symbol.kind(), symbol.line(), symbol.signature());
        }
        jdbc.update("INSERT INTO search_outbox(entry_id) VALUES(?)", entryId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Analysis> find(String workspaceId, String entryId) {
        return jdbc.query("""
                SELECT d.* FROM extracted_document d JOIN entry e ON e.id=d.entry_id
                WHERE e.workspace_id=? AND e.id=?
                """, (result, row) -> new Analysis(result.getString("text"), result.getString("media_type"),
                result.getString("status"), result.getString("error"), jdbc.query(
                "SELECT name,kind,line,signature FROM symbol WHERE entry_id=? ORDER BY line,name",
                (symbol, index) -> new Analysis.Symbol(symbol.getString("name"), symbol.getString("kind"),
                        symbol.getInt("line"), symbol.getString("signature")), entryId)),
                workspaceId, entryId).stream().findFirst();
    }
}
