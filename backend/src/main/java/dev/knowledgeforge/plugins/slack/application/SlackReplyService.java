package dev.knowledgeforge.plugins.slack.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.slack.domain.SlackReply;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SlackReplyService {
    public record PreviewInput(String requestId, String inboxItemId, String body) {}
    public record ExecuteInput(String requestId, String digest, boolean approved) {}
    public record View(String id, String workspaceId, String inboxItemId, SlackReplyAccess.Scope scope, String scopeHash,
                       String body, String digest, String status, int dispatchCount, String slackMessageTs, String failureCode,
                       String createdAt, String expiresAt, String updatedAt) {}
    private final SlackReplyStore store;
    private final LibraryStore library;
    private final Clock clock;
    private final JsonMapper json = new JsonMapper();

    @Autowired public SlackReplyService(SlackReplyStore store, LibraryStore library) {
        this(store, library, Clock.systemUTC());
    }
    public SlackReplyService(SlackReplyStore store, LibraryStore library, Clock clock) {
        this.store = store; this.library = library; this.clock = clock;
    }

    public static LibraryException readOnly() {
        return new LibraryException(403, "READ_ONLY: 외부 업무 시스템은 읽기 전용입니다. Slack 게시를 허용하지 않습니다.");
    }

    public View preview(String workspaceId, PreviewInput input) {
        throw readOnly();
    }

    public View execute(String workspaceId, String id, ExecuteInput input) {
        throw readOnly();
    }

    public View cancel(String workspaceId, String id) {
        workspace(workspaceId); owned(workspaceId, id);
        store.closePreview(workspaceId, id, "CANCELLED", clock.instant().toString());
        return view(owned(workspaceId, id));
    }
    public View acknowledge(String workspaceId, String id) {
        throw readOnly();
    }
    public View get(String workspaceId, String id) { workspace(workspaceId); return view(owned(workspaceId, id)); }
    public List<View> list(String workspaceId, String inboxItemId) {
        workspace(workspaceId); identifier(inboxItemId);
        return store.list(workspaceId, inboxItemId).stream().map(this::view).toList();
    }
    private SlackReply owned(String workspaceId, String id) {
        identifier(id);
        return store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "Slack 답장 영수증을 찾을 수 없습니다."));
    }
    private SlackReplyAccess.Scope scope(SlackReply reply) { return json.readValue(reply.scopeJson(), SlackReplyAccess.Scope.class); }
    private View view(SlackReply value) {
        return new View(value.id(), value.workspaceId(), value.inboxItemId(), scope(value), value.scopeHash(), value.body(), value.digest(), value.status(),
                value.dispatchCount(), value.slackMessageTs(), value.failureCode(), value.createdAt(), value.expiresAt(), value.updatedAt());
    }
    private void workspace(String id) { if (id == null || library.workspace(id).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다."); }
    private static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid(); }
    private static LibraryException invalid() { return new LibraryException(400, "Slack 답장 요청 형식 또는 길이가 올바르지 않습니다."); }
}
