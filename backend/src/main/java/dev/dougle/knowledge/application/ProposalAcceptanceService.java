package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.ProposalAcceptance;
import dev.dougle.library.application.LibraryException;
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
    private final MergeEvidencePlanner planner;

    public ProposalAcceptanceService(ProposalDecisionService decisions, GenerationComparisonService comparison,
                                     KnowledgeService knowledge, ProposalAcceptanceStore store, MergeEvidencePlanner planner) {
        this.decisions = decisions;
        this.comparison = comparison;
        this.knowledge = knowledge;
        this.store = store;
        this.planner = planner;
    }

    public record State(String status, int decisionRevision, ProposalAcceptance acceptance) {}
    public record MergePreview(int sourceVersion, int resultVersion, MergeEvidencePlanner.Plan plan) {}

    public MergePreview preview(String workspace, String job) {
        var documents = comparison.compare(workspace, job);
        if (documents.generated().card().latestVersion() != 1) {
            throw new LibraryException(409, "AI 결과 카드가 편집되었습니다. 최초 결과의 번호를 통합할 수 없습니다.");
        }
        return new MergePreview(documents.current().version().number(), 1,
                planner.plan(documents.current().version().evidence(), documents.generated().version().evidence(),
                        documents.generated().version().markdown()));
    }

    public State state(String workspace, String job) {
        var history = decisions.history(workspace, job);
        var accepted = store.find(workspace, job).orElse(null);
        return new State(accepted != null ? "ACCEPTED" : history.isEmpty() ? "OPEN" : history.getFirst().status(),
                history.isEmpty() ? 0 : history.getFirst().revision(), accepted);
    }

    public State accept(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                        int expectedResultVersion, String note, boolean confirmed) {
        return apply(workspace, job, expectedRevision, expectedSourceVersion, expectedResultVersion, note, confirmed, false, false, null, null);
    }

    public State merge(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                       int expectedResultVersion, String note, boolean confirmed, String title, String markdown) {
        return merge(workspace, job, expectedRevision, expectedSourceVersion, expectedResultVersion, note, confirmed, title, markdown, false);
    }

    public State merge(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                       int expectedResultVersion, String note, boolean confirmed, String title, String markdown, boolean combineEvidence) {
        return apply(workspace, job, expectedRevision, expectedSourceVersion, expectedResultVersion, note, confirmed, true, combineEvidence, title, markdown);
    }

    private State apply(String workspace, String job, int expectedRevision, int expectedSourceVersion,
                        int expectedResultVersion, String note, boolean confirmed, boolean manual, boolean combineEvidence, String title, String markdown) {
        var state = state(workspace, job);
        if (!"OPEN".equals(state.status()) || state.decisionRevision() != expectedRevision) {
            throw new LibraryException(409, "이미 채택했거나 처리 상태가 변경되었습니다. 거절한 제안은 먼저 재검토로 돌리세요.");
        }
        if (!confirmed || note == null || note.isBlank() || note.length() > 4000) {
            throw new LibraryException(400, "내용을 새 초안으로 반영할지 확인하고 이유를 1~4,000자로 입력하세요.");
        }
        var documents = comparison.compare(workspace, job);
        var current = documents.current();
        var generated = documents.generated();
        if (current.version().number() != expectedSourceVersion
                || (!manual && current.version().number() != documents.base().version().number())
                || generated.card().latestVersion() != expectedResultVersion || expectedResultVersion != 1) {
            throw new LibraryException(409, "문서 버전이 다르거나 결과 카드가 편집되었습니다. 최신 비교를 다시 확인하세요.");
        }
        var evidence = manual && combineEvidence
                ? planner.plan(current.version().evidence(), generated.version().evidence(), generated.version().markdown()).evidence()
                : generated.version().evidence();
        if (evidence.isEmpty() || (!combineEvidence && !evidence.equals(current.version().evidence()))
                || evidence.stream().anyMatch(item -> !"CURRENT".equals(item.freshness()))) {
            throw new LibraryException(409, "근거가 변경·삭제되었거나 연결이 다릅니다. 새 근거로 다시 작성하세요.");
        }
        String finalMarkdown = manual ? markdown : generated.version().markdown();
        if (finalMarkdown == null || finalMarkdown.isBlank() || finalMarkdown.length() > 100_000) {
            throw new LibraryException(400, "본문은 1~100,000자로 입력하세요.");
        }
        if (manual && current.version().title().equals(title == null ? null : title.strip())
                && current.version().markdown().equals(markdown)) {
            throw new LibraryException(400, "현재 문서와 내용이 같습니다. 반영할 내용을 편집하세요.");
        }
        var matcher = CITATION.matcher(finalMarkdown);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String reference = matcher.group(1);
            if (!reference.matches("[1-9][0-9]?") || Integer.parseInt(reference) > evidence.size()) {
                throw new LibraryException(400, "반영할 본문의 근거 번호가 유효하지 않습니다.");
            }
        }
        if (!found) throw new LibraryException(400, "반영할 본문에 [E1] 형식의 근거 인용이 필요합니다.");
        var applied = manual
                ? knowledge.reviseWithEvidence(workspace, current.card().id(), expectedSourceVersion, title, markdown, evidence)
                : knowledge.acceptGenerated(workspace, current.card().id(), expectedSourceVersion, generated);
        store.save(workspace, job, new ProposalAcceptance(expectedSourceVersion, 1, applied.version().number(),
                expectedRevision, note.strip(), Instant.now().toString(), manual ? "MANUAL" : "FULL"));
        return state(workspace, job);
    }
}
