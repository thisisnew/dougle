package dev.knowledgeforge.plugins.slack.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public interface SlackInboxStore {
    record ThreadLink(String workspaceId, String threadId) {}
    record State(Map<String, SlackInboxService.Route> routes, Map<String, SlackInboxService.Item> items,
                 Map<String, Instant> seen, Map<String, ThreadLink> threads) {
        public State() { this(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()); }
    }
    <T> T read(Function<State, T> operation);
    <T> T write(Function<State, T> operation);
    default long pendingCount(String workspaceId) {
        return read(data -> data.items().values().stream().filter(item -> item.workspaceId().equals(workspaceId)
                && item.sent() == null && !item.event().deleted()).count());
    }
    default int itemCount() { return read(data -> data.items().size()); }
}
