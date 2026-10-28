package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.ConversationRequest.History;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.EvidenceBundleService;
import dev.dougle.search.application.EvidenceBundleValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationHistoryService {
    private final ConversationService conversations;
    private final AnswerJobStore jobs;
    private final EvidenceBundleService bundles;
    private final EvidenceBundleValidator validator;
    public ConversationHistoryService(ConversationService conversations, AnswerJobStore jobs, EvidenceBundleService bundles, EvidenceBundleValidator validator) {
        this.conversations = conversations; this.jobs = jobs; this.bundles = bundles; this.validator = validator;
    }
    @Transactional(readOnly = true, noRollbackFor = LibraryException.class)
    public List<History> read(String workspaceId, String id, Integer revision, List<String> jobIds) {
        var room = conversations.get(workspaceId, id);
        if (revision == null || jobIds == null || jobIds.isEmpty() || jobIds.size() > 5
                || jobIds.stream().anyMatch(value -> value == null || value.isBlank()) || new HashSet<>(jobIds).size() != jobIds.size()) {
            throw new LibraryException(400, "서로 다른 이전 답변 1~5개와 보관함 revision을 선택하세요.");
        }
        if (room.conversation().revision() != revision) throw new LibraryException(409, "보관함이 변경되었습니다. 다시 조회하세요.");
        var selected = room.turns().stream().filter(turn -> jobIds.contains(turn.jobId())).toList();
        if (selected.size() != jobIds.size()) throw new LibraryException(409, "선택한 답변이 보관함에 없거나 삭제되었습니다.");
        var history = new ArrayList<History>();
        for (var turn : selected) {
            var job = jobs.find(workspaceId, turn.jobId()).orElseThrow(() -> new LibraryException(409, "이전 답변이 삭제되었습니다."));
            if (!job.status().equals("SUCCEEDED") || job.answer() == null) throw new LibraryException(409, "이전 답변이 완료 상태가 아닙니다.");
            if (!validator.check(workspaceId, job.bundleId()).eligible()) throw new LibraryException(409, "이전 답변의 근거가 변경되거나 삭제되었습니다. 해당 답변을 제외하세요.");
            var previous = bundles.get(workspaceId, job.bundleId());
            history.add(new History(job.id(), turn.position(), previous.id(), previous.digest(), previous.payload().question(), job.answer()));
        }
        return List.copyOf(history);
    }
}
