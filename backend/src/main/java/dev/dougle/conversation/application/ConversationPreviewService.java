package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.ConversationRequest.History;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.ContextPrompt;
import dev.dougle.search.application.EvidenceAnswerPreview;
import dev.dougle.search.application.EvidenceBundleService;
import dev.dougle.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationPreviewService {
    public record Request(String bundleId, Integer expectedRevision, List<String> jobIds, String provider, String requestedModel) {}
    public record Preview(String conversationId, int revision, String provider, String requestedModel, EvidenceBundle bundle,
                          List<History> history, String digest, String prompt, int characters, int maxCharacters) {}
    private final ConversationHistoryService histories;
    private final EvidenceBundleService bundles;
    private final EvidenceAnswerPreview previews;
    public ConversationPreviewService(ConversationHistoryService histories, EvidenceBundleService bundles, EvidenceAnswerPreview previews) {
        this.histories = histories; this.bundles = bundles; this.previews = previews;
    }
    @Transactional(readOnly = true)
    public Preview prepare(String workspaceId, String id, Request request) {
        if (request == null || request.bundleId() == null || request.bundleId().isBlank()) throw new LibraryException(400, "새 질문의 근거 묶음을 선택하세요.");
        var history = histories.read(workspaceId, id, request.expectedRevision(), request.jobIds());
        var current = previews.prepareBase(workspaceId, request.bundleId(), request.provider(), request.requestedModel());
        String prompt = ContextPrompt.prefix(history) + current.prompt();
        if (prompt.length() > 20000) throw new LibraryException(413, "이전 문맥을 포함한 요청이 20,000자를 초과합니다. 답변 선택이나 근거 범위를 줄이세요.");
        String digest = ContextPrompt.digest(workspaceId, id, request.expectedRevision(), current.digest(), current.provider(), current.requestedModel(), prompt);
        return new Preview(id, request.expectedRevision(), current.provider(), current.requestedModel(),
                bundles.get(workspaceId, request.bundleId()), history, digest, prompt, prompt.length(), 20000);
    }
}
