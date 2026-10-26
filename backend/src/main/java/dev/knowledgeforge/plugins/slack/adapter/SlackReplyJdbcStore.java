package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackReplyStore;
import dev.knowledgeforge.plugins.slack.domain.SlackReply;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SlackReplyJdbcStore implements SlackReplyStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final RowMapper<SlackReply> ROW = (row, index) -> new SlackReply(row.getString("id"), row.getString("workspace_id"),
            row.getString("inbox_item_id"), row.getString("preview_request_id"), row.getString("scope_hash"), row.getString("scope_json"),
            row.getString("body"), row.getString("digest"), row.getString("status"), row.getString("execute_request_id"),
            row.getInt("dispatch_count"), row.getString("slack_message_ts"), row.getString("failure_code"), row.getString("created_at"),
            row.getString("expires_at"), row.getString("updated_at"));

    public SlackReplyJdbcStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(transactions);
    }
    @Override public <T> T transaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    @Override public Optional<SlackReply> find(String workspaceId, String id) {
        return jdbc.query("SELECT * FROM slack_reply WHERE workspace_id=? AND id=?", ROW, workspaceId, id).stream().findFirst();
    }
    @Override public Optional<SlackReply> previewRequest(String workspaceId, String requestId) {
        return jdbc.query("SELECT * FROM slack_reply WHERE workspace_id=? AND preview_request_id=?", ROW, workspaceId, requestId).stream().findFirst();
    }
    @Override public List<SlackReply> list(String workspaceId, String inboxItemId) {
        return jdbc.query("SELECT * FROM slack_reply WHERE workspace_id=? AND inbox_item_id=? ORDER BY created_at DESC,id LIMIT 1000", ROW, workspaceId, inboxItemId);
    }
    @Override public int count() { return jdbc.queryForObject("SELECT count(*) FROM slack_reply", Integer.class); }
    @Override public void insert(SlackReply value) {
        jdbc.update("INSERT INTO slack_reply (id,workspace_id,inbox_item_id,preview_request_id,scope_hash,scope_json,body,digest,status,execute_request_id,dispatch_count,slack_message_ts,failure_code,created_at,expires_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.workspaceId(), value.inboxItemId(), value.previewRequestId(), value.scopeHash(), value.scopeJson(), value.body(), value.digest(), value.status(),
                value.executeRequestId(), value.dispatchCount(), value.slackMessageTs(), value.failureCode(), value.createdAt(), value.expiresAt(), value.updatedAt());
    }
    @Override public boolean claim(String workspaceId, String id, String requestId, String now) {
        return jdbc.update("UPDATE slack_reply SET status='SENDING',execute_request_id=?,dispatch_count=1,updated_at=? WHERE workspace_id=? AND id=? AND status='PREVIEW' AND execute_request_id IS NULL",
                requestId, now, workspaceId, id) == 1;
    }
    @Override public void finish(String workspaceId, String id, String status, String timestamp, String code, String now) {
        jdbc.update("UPDATE slack_reply SET status=?,slack_message_ts=?,failure_code=?,updated_at=?,dispatch_count=CASE WHEN ? IN ('SCOPE_CHANGED_BEFORE_POST','LOCAL_VALIDATION') THEN 0 ELSE dispatch_count END WHERE workspace_id=? AND id=? AND status='SENDING'",
                status, timestamp, code, now, code, workspaceId, id);
    }
    @Override public void closePreview(String workspaceId, String id, String status, String now) {
        jdbc.update("UPDATE slack_reply SET status=?,updated_at=? WHERE workspace_id=? AND id=? AND status='PREVIEW'", status, now, workspaceId, id);
    }
    @Override public void acknowledge(String workspaceId, String id, String now) {
        jdbc.update("UPDATE slack_reply SET failure_code='UNCERTAIN_ACKNOWLEDGED',updated_at=? WHERE workspace_id=? AND id=? AND status='UNCERTAIN'", now, workspaceId, id);
    }
}
