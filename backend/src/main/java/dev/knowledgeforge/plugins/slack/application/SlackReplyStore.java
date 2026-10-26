package dev.knowledgeforge.plugins.slack.application;

import dev.knowledgeforge.plugins.slack.domain.SlackReply;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public interface SlackReplyStore {
    <T> T transaction(Supplier<T> action);
    Optional<SlackReply> find(String workspaceId, String id);
    Optional<SlackReply> previewRequest(String workspaceId, String requestId);
    List<SlackReply> list(String workspaceId, String inboxItemId);
    int count();
    void insert(SlackReply reply);
    boolean claim(String workspaceId, String id, String requestId, String now);
    void finish(String workspaceId, String id, String status, String timestamp, String code, String now);
    void closePreview(String workspaceId, String id, String status, String now);
    void acknowledge(String workspaceId, String id, String now);
}
