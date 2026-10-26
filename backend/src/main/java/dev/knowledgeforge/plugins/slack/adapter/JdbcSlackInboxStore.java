package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackInboxService;
import dev.knowledgeforge.plugins.slack.application.SlackInboxStore;
import java.time.Instant;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcSlackInboxStore implements SlackInboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcSlackInboxStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions);
    }

    @Override public synchronized <T> T read(Function<State, T> operation) {
        return transactions.execute(status -> operation.apply(load()));
    }

    @Override public synchronized long pendingCount(String workspaceId) {
        return jdbc.queryForObject("SELECT count(*) FROM slack_inbox_item WHERE workspace_id=? AND json_extract(payload_json,'$.sent') IS NULL AND coalesce(json_extract(payload_json,'$.event.deleted'),0)=0",
                Long.class, workspaceId);
    }

    @Override public synchronized int itemCount() { return jdbc.queryForObject("SELECT count(*) FROM slack_inbox_item", Integer.class); }

    @Override public synchronized <T> T write(Function<State, T> operation) {
        return transactions.execute(status -> {
            var state = load();
            T result = operation.apply(state);
            if (state.routes().size() > 100 || state.items().size() > SlackInboxService.MAX_ITEMS
                    || state.seen().size() > SlackInboxService.MAX_SEEN || state.threads().size() > 1000) {
                throw new IllegalStateException("Slack inbox capacity exceeded");
            }
            jdbc.update("DELETE FROM slack_inbox_route");
            state.routes().forEach((key, route) -> jdbc.update("INSERT INTO slack_inbox_route VALUES (?,?,?)",
                    key, route.workspaceId(), json.writeValueAsString(route)));
            jdbc.update("DELETE FROM slack_inbox_item");
            state.items().forEach((key, item) -> jdbc.update("INSERT INTO slack_inbox_item VALUES (?,?,?)",
                    key, item.workspaceId(), json.writeValueAsString(item)));
            jdbc.update("DELETE FROM slack_inbox_seen");
            state.seen().forEach((key, instant) -> jdbc.update("INSERT INTO slack_inbox_seen VALUES (?,?)", key, instant.toString()));
            jdbc.update("DELETE FROM slack_inbox_thread");
            state.threads().forEach((key, link) -> jdbc.update("INSERT INTO slack_inbox_thread VALUES (?,?,?)", key, link.workspaceId(), link.threadId()));
            return result;
        });
    }

    private State load() {
        var state = new State();
        jdbc.query("SELECT route_key,payload_json FROM slack_inbox_route ORDER BY route_key", row -> {
            state.routes().put(row.getString(1), json.readValue(row.getString(2), SlackInboxService.Route.class));
        });
        jdbc.query("SELECT id,payload_json FROM slack_inbox_item ORDER BY id", row -> {
            state.items().put(row.getString(1), json.readValue(row.getString(2), SlackInboxService.Item.class));
        });
        jdbc.query("SELECT event_key,received_at FROM slack_inbox_seen", row -> {
            state.seen().put(row.getString(1), Instant.parse(row.getString(2)));
        });
        jdbc.query("SELECT thread_key,workspace_id,thread_id FROM slack_inbox_thread", row -> {
            state.threads().put(row.getString(1), new ThreadLink(row.getString(2), row.getString(3)));
        });
        return state;
    }
}
