package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.ConversationRequest;
import dev.dougle.library.application.LibraryException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationRequestService {
    public record Freeze(ConversationPreviewService.Request selection, String digest) {}
    private final ConversationRequestStore store;
    private final ConversationPreviewService previews;
    private final AnswerApprovalStore approvals;
    private final AnswerJobStore jobs;
    private final ConversationStore conversations;
    public ConversationRequestService(ConversationRequestStore store, ConversationPreviewService previews, AnswerApprovalStore approvals, AnswerJobStore jobs, ConversationStore conversations) {
        this.store = store; this.previews = previews; this.approvals = approvals; this.jobs = jobs;
        this.conversations = conversations;
    }
    @Transactional
    public ConversationRequest freeze(String workspaceId, String id, Freeze input) {
        if (input == null || input.selection() == null) throw new LibraryException(400, "문맥 미리보기를 먼저 확인하세요.");
        var preview = previews.prepare(workspaceId, id, input.selection());
        if (!preview.digest().equals(input.digest())) throw new LibraryException(409, "미리보기가 변경되었습니다. 다시 확인하세요.");
        String bundleId = preview.bundle().id();
        var previous = store.find(workspaceId, bundleId);
        if (previous.isPresent()) {
            if (!previous.get().digest().equals(preview.digest())) throw new LibraryException(409, "이미 다른 문맥으로 고정한 묶음입니다. 새 묶음을 만드세요.");
            return previous.get();
        }
        if (approvals.find(bundleId).isPresent() || jobs.list(workspaceId).stream().anyMatch(job -> job.bundleId().equals(bundleId))) {
            throw new LibraryException(409, "승인 또는 작업이 있는 묶음의 문맥을 바꿀 수 없습니다. 새 묶음을 만드세요.");
        }
        if (conversations.turns(workspaceId, id).size() >= 100) throw new LibraryException(409, "대화 보관함의 답변 한도 100개에 도달했습니다.");
        var request = new ConversationRequest(UUID.randomUUID().toString(), workspaceId, id, preview.revision(), bundleId,
                preview.provider(), preview.requestedModel(), preview.history(), preview.digest(), preview.prompt(), Instant.now().toString());
        store.create(request);
        return request;
    }
}
