package dev.dougle.conversation.adapter;

import dev.dougle.conversation.application.ConversationRequestStore;
import dev.dougle.conversation.domain.ConversationRequest;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcConversationRequestStore implements ConversationRequestStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcConversationRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ConversationRequest> find(String workspaceId, String bundleId) {
        return jdbc.query("SELECT snapshot_json FROM conversation_request WHERE workspace_id=? AND bundle_id=?",
                (row, index) -> json.readValue(row.getString(1), ConversationRequest.class), workspaceId, bundleId).stream().findFirst();
    }
    public void create(ConversationRequest request) {
        jdbc.update("INSERT INTO conversation_request VALUES(?,?,?,?)", request.id(), request.workspaceId(), request.bundleId(), json.writeValueAsString(request));
    }
}
