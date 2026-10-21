package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.ProposalAcceptance;
import dev.knowledgeforge.library.application.LibraryException;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProposalAcceptanceService {
    private static final Pattern CITATION = Pattern.compile("\\[E([^]\\r\\n]*)]");
    private final ProposalDecisionService decisions;
    private final GenerationComparisonService comparison;
    private final KnowledgeService knowledge;
    private final ProposalAcceptanceStore store;

    public ProposalAcceptanceService(ProposalDecisionService decisions, GenerationComparisonService comparison,
                                     KnowledgeService knowledge, ProposalAcceptanceStore store) {
        this.decisions = decisions;
        this.comparison = comparison;
        this.knowledge = knowledge;
        this.store = store;
    }

    public record State(String status, int decisionRevision, ProposalAcceptance acceptance) {}

    public State state(String workspace, String job) {
        var history = decisions.history(workspace, job);
        var accepted = store.find(workspace, job).orElse(null);
        return new State(accepted != null ? "ACCEPTED" : history.isEmpty() ? "OPEN" : history.getFirst().status(),
                history.isEmpty() ? 0 : history.getFirst().revision(), accepted);
    }

    public State accept(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                        int expectedResultVersion, String note, boolean confirmed) {
        var state = state(workspace, job);
        if (!"OPEN".equals(state.status()) || state.decisionRevision() != expectedRevision) {
            throw new LibraryException(409, "이미 채택했거나 처리 상태가 변경되었습니다. 거절한 제안은 먼저 재검토로 돌리세요.");
        }
        if (!confirmed || note == null || note.isBlank() || note.length() > 4000) {
            throw new LibraryException(400, "전체 내용을 새 초안으로 반영할지 확인하고 이유를 1~4,000자로 입력하세요.");
        }
        var documents = comparison.compare(workspace, job);
        var current = documents.current();
        var generated = documents.generated();
        if (current.version().number() != expectedSourceVersion
                || current.version().number() != documents.base().version().number()
                || generated.card().latestVersion() != expectedResultVersion || expectedResultVersion != 1) {
            throw new LibraryException(409, "생성 후 문서가 편집되었습니다. 전체 채택 대신 현재 문서에서 직접 병합하세요.");
        }
        var evidence = generated.version().evidence();
        if (evidence.isEmpty() || !evidence.equals(current.version().evidence())
                || evidence.stream().anyMatch(item -> !"CURRENT".equals(item.freshness()))) {
            throw new LibraryException(409, "근거가 변경·삭제되었거나 연결이 다릅니다. 새 근거로 다시 작성하세요.");
        }
        var matcher = CITATION.matcher(generated.version().markdown());
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String reference = matcher.group(1);
            if (!reference.matches("[1-9][0-9]?") || Integer.parseInt(reference) > evidence.size()) {
                throw new LibraryException(400, "AI 결과의 근거 번호가 유효하지 않습니다.");
            }
        }
        if (!found) throw new LibraryException(400, "AI 결과에 [E1] 형식의 근거 인용이 필요합니다.");
        var applied = knowledge.acceptGenerated(workspace, current.card().id(), expectedSourceVersion, generated);
        store.save(workspace, job, new ProposalAcceptance(expectedSourceVersion, 1, applied.version().number(),
                expectedRevision, note.strip(), Instant.now().toString()));
        return state(workspace, job);
    }
}
