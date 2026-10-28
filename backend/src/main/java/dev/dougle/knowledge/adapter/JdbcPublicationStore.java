package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.PublicationStore;
import dev.dougle.knowledge.domain.Publication.*;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPublicationStore implements PublicationStore {
    private final JdbcTemplate jdbc;

    public JdbcPublicationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Review> review(String cardId, int version) {
        return jdbc.query("SELECT * FROM knowledge_review WHERE card_id=? AND version=?",
                (row, index) -> new Review(row.getInt("version"), row.getString("reviewer"), row.getString("note"), row.getString("reviewed_at")),
                cardId, version).stream().findFirst();
    }

    @Override
    public Optional<Release> publication(String cardId) {
        return jdbc.query("SELECT * FROM knowledge_publication WHERE card_id=? ORDER BY revision DESC LIMIT 1",
                (row, index) -> new Release(row.getInt("revision"), row.getInt("version"), row.getString("published_at")),
                cardId).stream().findFirst();
    }

    @Override
    public void review(String cardId, Review review) {
        jdbc.update("INSERT INTO knowledge_review(card_id,version,reviewer,note,reviewed_at) VALUES(?,?,?,?,?)",
                cardId, review.version(), review.reviewer(), review.note(), review.reviewedAt());
    }

    @Override
    public boolean publish(String cardId, int expectedVersion, int expectedRevision, String publishedAt) {
        return jdbc.update("""
                INSERT INTO knowledge_publication(card_id,revision,version,published_at)
                SELECT id,?,?,? FROM knowledge_card WHERE id=? AND latest_version=?
                  AND COALESCE((SELECT MAX(revision) FROM knowledge_publication WHERE card_id=?),0)=?
                  AND NOT EXISTS(SELECT 1 FROM knowledge_publication WHERE card_id=? AND version=?)
                """, expectedRevision + 1, expectedVersion, publishedAt, cardId, expectedVersion,
                cardId, expectedRevision, cardId, expectedVersion) == 1;
    }
}
