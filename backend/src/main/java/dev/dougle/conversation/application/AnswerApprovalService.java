package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.AnswerApproval;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.EvidenceAnswerPreview;
import dev.dougle.search.application.EvidenceBundleService;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerApprovalService {
    public record State(String status, AnswerApproval approval) {}
    private final AnswerApprovalStore store;
    private final EvidenceBundleService bundles;
    private final EvidenceAnswerPreview previews;
    private final AnswerJobStore jobs;

    public AnswerApprovalService(AnswerApprovalStore store, EvidenceBundleService bundles, EvidenceAnswerPreview previews, AnswerJobStore jobs) {
        this.store = store;
        this.bundles = bundles;
        this.previews = previews;
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public State get(String workspaceId, String bundleId) {
        bundles.get(workspaceId, bundleId);
        var approval = store.find(bundleId).orElse(null);
        if (approval == null) return new State("NONE", null);
        if (approval.revokedAt() != null) return new State("REVOKED", approval);
        if (jobs.byApproval(approval.id()).isPresent()) return new State("CONSUMED", approval);
        if (!Instant.now().isBefore(Instant.parse(approval.expiresAt()))) return new State("EXPIRED", approval);
        boolean valid = previews.current(workspaceId, bundleId, approval.provider(), approval.requestedModel())
                .filter(preview -> preview.digest().equals(approval.digest())).isPresent();
        return new State(valid ? "APPROVED" : "INVALIDATED", approval);
    }

    @Transactional
    public State approve(String workspaceId, String bundleId, String provider, String requestedModel,
                         String digest, boolean acknowledged, String expectedApprovalId) {
        bundles.get(workspaceId, bundleId);
        if (!acknowledged) throw new LibraryException(400, "외부 전송 대상과 요청 전문을 확인하고 명시적으로 동의하세요.");
        var current = store.find(bundleId).orElse(null);
        if (!Objects.equals(expectedApprovalId, current == null ? null : current.id())) {
            throw new LibraryException(409, "다른 화면에서 승인이 변경되었습니다. 승인 상태를 다시 확인하세요.");
        }
        var preview = previews.prepare(workspaceId, bundleId, provider, requestedModel);
        if (!preview.digest().equals(digest)) throw new LibraryException(409, "요청 조건이 변경되었습니다. 새 미리보기를 확인하고 다시 승인하세요.");
        var now = Instant.now();
        var approval = new AnswerApproval(UUID.randomUUID().toString(), bundleId, preview.provider(), preview.requestedModel(),
                preview.digest(), now.toString(), now.plus(Duration.ofMinutes(10)).toString(), null);
        store.save(approval);
        return new State("APPROVED", approval);
    }

    @Transactional
    public State revoke(String workspaceId, String bundleId, String approvalId) {
        bundles.get(workspaceId, bundleId);
        var current = store.find(bundleId).orElseThrow(() -> new LibraryException(404, "승인 기록이 없습니다."));
        if (!current.id().equals(approvalId)) throw new LibraryException(409, "승인이 교체되었습니다. 상태를 다시 확인하세요.");
        store.revoke(bundleId, approvalId, Instant.now().toString());
        return new State("REVOKED", store.find(bundleId).orElseThrow());
    }
}
