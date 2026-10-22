package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.ConversationStore;
import dev.knowledgeforge.conversation.domain.Conversation;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcConversationStore implements ConversationStore {
    private final JdbcTemplate jdbc;
    private final RowMapper<Conversation> mapper = (row, index) -> new Conversation(row.getString("id"), row.getString("workspace_id"),
            row.getString("title"), row.getInt("revision"), row.getString("created_at"));
    public JdbcConversationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Conversation> list(String workspaceId) {
        return jdbc.query("SELECT * FROM conversation WHERE workspace_id=? ORDER BY created_at DESC,id", mapper, workspaceId);
    }
    public Optional<Conversation> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM conversation WHERE workspace_id=? AND id=?", mapper, workspaceId, id).stream().findFirst();
    }
    public void create(Conversation conversation) {
        jdbc.update("INSERT INTO conversation VALUES(?,?,?,?,?)", conversation.id(), conversation.workspaceId(), conversation.title(), conversation.revision(), conversation.createdAt());
    }
    public List<Conversation.Turn> turns(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM conversation_turn WHERE workspace_id=? AND conversation_id=? ORDER BY position",
                (row, index) -> new Conversation.Turn(row.getString("job_id"), row.getInt("position"), row.getString("added_at")), workspaceId, id);
    }
    public void append(String workspaceId, String id, String jobId, int position, String addedAt) {
        jdbc.update("INSERT INTO conversation_turn VALUES(?,?,?,?,?)", workspaceId, id, jobId, position, addedAt);
        jdbc.update("UPDATE conversation SET revision=? WHERE workspace_id=? AND id=?", position, workspaceId, id);
    }
    public void delete(String workspaceId, String id) {
        jdbc.update("DELETE FROM conversation WHERE workspace_id=? AND id=?", workspaceId, id);
    }
}
