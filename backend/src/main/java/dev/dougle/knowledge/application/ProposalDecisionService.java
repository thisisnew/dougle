package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.ProposalDecision;
import dev.dougle.library.application.LibraryException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProposalDecisionService {
    private final GenerationJobStore jobs;
    private final GenerationComparisonService comparison;
    private final ProposalDecisionStore store;
    private final ProposalAcceptanceStore acceptances;

    public ProposalDecisionService(GenerationJobStore jobs, GenerationComparisonService comparison, ProposalDecisionStore store, ProposalAcceptanceStore acceptances) {
        this.jobs = jobs;
        this.comparison = comparison;
        this.store = store;
        this.acceptances = acceptances;
    }

    public List<ProposalDecision> history(String workspace, String job) {
        var found = jobs.find(workspace, job).orElseThrow(() -> new LibraryException(404, "AI 작업을 찾을 수 없습니다."));
        if (!"SUCCEEDED".equals(found.status()) || found.resultCardId() == null) {
            throw new LibraryException(409, "완료된 AI 작업만 처리할 수 있습니다.");
        }
        return store.history(workspace, job);
    }

    public List<ProposalDecision> decide(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                                         int expectedResultVersion, String status, String note) {
        var history = history(workspace, job);
        if (acceptances.find(workspace, job).isPresent()) {
            throw new LibraryException(409, "이미 채택한 제안입니다. 반영된 초안은 지식 편집에서 수정하세요.");
        }
        if (!"REJECTED".equals(status) && !"OPEN".equals(status)) {
            throw new LibraryException(400, "거절 또는 재검토만 기록할 수 있습니다.");
        }
        if (note == null || note.isBlank() || note.length() > 4000) {
            throw new LibraryException(400, "처리 이유를 1~4,000자로 입력하세요.");
        }
        int revision = history.isEmpty() ? 0 : history.getFirst().revision();
        String previous = history.isEmpty() ? "OPEN" : history.getFirst().status();
        if (expectedRevision != revision || previous.equals(status)) {
            throw new LibraryException(409, "처리 상태가 변경되었거나 같은 상태입니다. 기록을 다시 확인하세요.");
        }
        var documents = comparison.compare(workspace, job);
        if (documents.current().card().latestVersion() != expectedSourceVersion
                || documents.generated().card().latestVersion() != expectedResultVersion) {
            throw new LibraryException(409, "문서가 변경되었습니다. 비교를 새로고침한 뒤 다시 판단하세요.");
        }
        var decision = new ProposalDecision(revision + 1, status, expectedSourceVersion, expectedResultVersion,
                note.strip(), Instant.now().toString());
        if (!store.append(workspace, job, decision)) {
            throw new LibraryException(409, "처리 기록이 변경되었습니다. 새로고침하세요.");
        }
        return store.history(workspace, job);
    }
}
