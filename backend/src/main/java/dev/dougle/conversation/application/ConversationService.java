package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.Conversation;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConversationService {
    private final ConversationStore store;
    private final LibraryStore library;
    private final AnswerJobStore jobs;
    public ConversationService(ConversationStore store, LibraryStore library, AnswerJobStore jobs) {
        this.store = store; this.library = library; this.jobs = jobs;
    }
    public record Detail(Conversation conversation, List<Conversation.Turn> turns) {}
    public List<Conversation> list(String workspaceId) {
        requireWorkspace(workspaceId);
        return store.list(workspaceId);
    }
    public Conversation create(String workspaceId, String title) {
        requireWorkspace(workspaceId);
        if (title == null || title.isBlank() || title.strip().length() > 120 || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new LibraryException(400, "대화 보관함 이름은 제어 문자 없이 1~120자로 입력하세요.");
        }
        if (store.list(workspaceId).size() >= 100) throw new LibraryException(409, "대화 보관함은 Workspace당 최대 100개입니다.");
        var conversation = new Conversation(UUID.randomUUID().toString(), workspaceId, title.strip(), 0, Instant.now().toString());
        store.create(conversation);
        return conversation;
    }
    @Transactional(readOnly = true, noRollbackFor = LibraryException.class)
    public Detail get(String workspaceId, String id) {
        requireWorkspace(workspaceId);
        var conversation = store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "대화 보관함이 없습니다."));
        return new Detail(conversation, store.turns(workspaceId, id));
    }
    public Detail append(String workspaceId, String id, String jobId, Integer expectedRevision) {
        var detail = get(workspaceId, id);
        if (jobId == null || jobId.isBlank() || expectedRevision == null || expectedRevision < 0) {
            throw new LibraryException(400, "답변 작업과 조회한 보관함 revision이 필요합니다.");
        }
        var job = jobs.find(workspaceId, jobId).orElseThrow(() -> new LibraryException(404, "답변 작업이 없습니다."));
        if (!job.status().equals("SUCCEEDED") || job.answer() == null) throw new LibraryException(409, "완료 답변만 보관함에 추가할 수 있습니다.");
        if (detail.turns().stream().anyMatch(turn -> turn.jobId().equals(jobId))) return detail;
        if (detail.conversation().revision() != expectedRevision) throw new LibraryException(409, "보관함이 변경되었습니다. 다시 조회하세요.");
        if (detail.turns().size() >= 100) throw new LibraryException(409, "보관함에는 최대 100개 답변을 연결합니다.");
        store.append(workspaceId, id, jobId, expectedRevision + 1, Instant.now().toString());
        return get(workspaceId, id);
    }
    public void delete(String workspaceId, String id) {
        get(workspaceId, id);
        store.delete(workspaceId, id);
    }
    private void requireWorkspace(String workspaceId) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace가 없습니다."));
    }
}
