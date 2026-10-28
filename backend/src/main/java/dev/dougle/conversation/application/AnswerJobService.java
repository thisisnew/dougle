package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.search.application.EvidenceAnswerPreview;
import dev.dougle.search.application.EvidenceBundleService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnswerJobService {
    public record Request(String requestId, String approvalId, String digest, boolean approved) {}
    private final AnswerJobStore store;
    private final AnswerApprovalStore approvals;
    private final EvidenceAnswerPreview previews;
    private final EvidenceBundleService bundles;
    private final LibraryStore library;
    private final AnswerModel model;
    private final ConversationCompletion completion;
    private final TransactionTemplate transaction;
    private AnswerJob active;
    private Thread worker;
    private boolean cancelled;
    private boolean closing;
    private String partialText = "";
    public record Partial(String workspaceId, String jobId, String bundleId, String text, boolean unverified) {}

    public AnswerJobService(AnswerJobStore store, AnswerApprovalStore approvals, EvidenceAnswerPreview previews,
                            EvidenceBundleService bundles, LibraryStore library, AnswerModel model, PlatformTransactionManager manager,
                            ConversationCompletion completion) {
        this.store = store; this.approvals = approvals; this.previews = previews; this.bundles = bundles;
        this.library = library; this.model = model; this.transaction = new TransactionTemplate(manager);
        this.completion = completion;
    }

    public synchronized AnswerJob submit(String workspaceId, String bundleId, Request request) {
        requireWorkspace(workspaceId);
        if (!request.approved()) throw new LibraryException(403, "실제 외부 전송을 명시적으로 확인하세요.");
        if (request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{16,64}")) {
            throw new LibraryException(400, "작업 ID는 영문·숫자·하이픈·밑줄 16~64자여야 합니다.");
        }
        var previous = store.find(workspaceId, request.requestId());
        if (previous.isPresent()) {
            var job = previous.get();
            if (!job.bundleId().equals(bundleId) || !job.approvalId().equals(request.approvalId()) || !job.digest().equals(request.digest())) {
                throw new LibraryException(409, "같은 작업 ID를 다른 요청에 사용할 수 없습니다.");
            }
            return job;
        }
        if (closing) throw new LibraryException(503, "앱이 종료 중입니다.");
        if (worker != null) throw new LibraryException(409, "다른 답변 작업이 실행 또는 취소 중입니다.");
        var job = transaction.execute(status -> {
            bundles.get(workspaceId, bundleId);
            var approval = approvals.find(bundleId).orElseThrow(() -> new LibraryException(403, "승인 기록이 없습니다."));
            if (!approval.id().equals(request.approvalId()) || !approval.digest().equals(request.digest())
                    || approval.revokedAt() != null || !Instant.now().isBefore(Instant.parse(approval.expiresAt()))) {
                throw new LibraryException(409, "승인 조건이 변경·철회·만료되었습니다. 다시 확인하세요.");
            }
            if (store.byApproval(approval.id()).isPresent()) throw new LibraryException(409, "이미 사용한 승인입니다. 새 요청은 다시 승인하세요.");
            if (store.list(workspaceId).size() >= 100) throw new LibraryException(409, "답변 작업 보관 한도 100개입니다. 불필요한 근거 묶음을 삭제하세요.");
            var preview = previews.prepare(workspaceId, bundleId, approval.provider(), approval.requestedModel());
            if (!preview.digest().equals(request.digest())) throw new LibraryException(409, "요청 내용이 변경되었습니다. 다시 승인하세요.");
            String now = Instant.now().toString();
            var created = new AnswerJob(request.requestId(), workspaceId, bundleId, approval.id(), approval.provider(),
                    approval.requestedModel(), approval.digest(), "QUEUED", null, null, now, now);
            store.create(created);
            return created;
        });
        active = job; cancelled = false; partialText = "";
        worker = Thread.ofVirtual().name("forge-answer-" + job.id()).unstarted(() -> run(job));
        try { worker.start(); }
        catch (RuntimeException exception) {
            worker = null; active = null;
            store.update(workspaceId, job.id(), "FAILED", null, "답변 실행기를 시작하지 못했습니다. 다시 승인하세요.");
            throw new LibraryException(503, "답변 실행기를 시작하지 못했습니다.");
        }
        return job;
    }

    public AnswerJob get(String workspaceId, String id) {
        return store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "답변 작업을 찾을 수 없습니다."));
    }
    public List<AnswerJob> list(String workspaceId) { requireWorkspace(workspaceId); return store.list(workspaceId); }

    public Partial partial(String workspaceId, String id) {
        AnswerJob observed;
        synchronized (this) { observed = active; }
        var job = get(workspaceId, id);
        synchronized (this) {
            String text = active != null && active == observed && active.id().equals(id) && active.workspaceId().equals(workspaceId)
                    && job.status().equals("RUNNING") && !cancelled ? partialText : "";
            return new Partial(workspaceId, id, job.bundleId(), text, true);
        }
    }

    private synchronized void receiveText(AnswerJob job, String text) {
        if (active != job || cancelled || closing) return;
        if (text == null || text.length() > 20000) throw new LibraryException(502, "임시 출력 한도를 초과했습니다.");
        partialText = text;
    }

    public synchronized AnswerJob cancel(String workspaceId, String id) {
        var job = get(workspaceId, id);
        if (job.active() && active != null && active.id().equals(id) && active.workspaceId().equals(workspaceId)) stop();
        return get(workspaceId, id);
    }

    private void stop() {
        cancelled = true; partialText = "";
        store.update(active.workspaceId(), active.id(), "CANCELLING", null, null);
        worker.interrupt();
    }

    private void run(AnswerJob job) {
        try {
            record Prepared(String prompt, java.util.List<dev.dougle.knowledge.application.ModelImage> images) {}
            Prepared prepared;
            synchronized (this) {
                prepared = transaction.execute(status -> {
                    checkActive(job, true);
                    var preview = previews.prepare(job.workspaceId(), job.bundleId(), job.provider(), job.requestedModel());
                    if (!preview.digest().equals(job.digest())) throw new LibraryException(409, "전송 직전 요청 내용이 변경되었습니다.");
                    if (!store.update(job.workspaceId(), job.id(), "RUNNING", null, null)) throw new CancellationException();
                    return new Prepared(preview.prompt(), bundles.images(job.workspaceId(), job.bundleId()));
                });
            }
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            String raw = !prepared.images().isEmpty()
                    ? model.generateWithImages(job.provider(), prepared.prompt(), job.requestedModel(), prepared.images(), text -> receiveText(job, text))
                    : model instanceof StreamingAnswerModel streaming
                        ? streaming.generateWithText(job.provider(), prepared.prompt(), job.requestedModel(), text -> receiveText(job, text))
                        : model.generate(job.provider(), prepared.prompt(), job.requestedModel());
            synchronized (this) {
                transaction.executeWithoutResult(status -> {
                    checkActive(job, false);
                    var preview = previews.prepare(job.workspaceId(), job.bundleId(), job.provider(), job.requestedModel());
                    if (!preview.digest().equals(job.digest())) throw new LibraryException(409, "생성 중 근거가 변경되어 답변을 저장하지 않았습니다.");
                    String answer = AnswerOutput.validate(raw, bundles.get(job.workspaceId(), job.bundleId()));
                    if (!store.update(job.workspaceId(), job.id(), "SUCCEEDED", answer, null)) throw new CancellationException();
                    completion.attach(job.workspaceId(), job.bundleId(), job.id());
                });
            }
        } catch (Exception exception) {
            synchronized (this) {
                Thread.interrupted();
                String error = exception instanceof LibraryException problem ? problem.getMessage() : "답변 작업에 실패했습니다. 다시 승인해 새 작업을 시작하세요.";
                store.update(job.workspaceId(), job.id(), cancelled ? "CANCELLED" : "FAILED", null, cancelled ? null : error);
            }
        } finally {
            synchronized (this) { active = null; worker = null; cancelled = false; partialText = ""; }
        }
    }

    private void checkActive(AnswerJob job, boolean beforeDispatch) {
        if (cancelled || Thread.currentThread().isInterrupted() || store.find(job.workspaceId(), job.id()).isEmpty()) throw new CancellationException();
        var approval = approvals.find(job.bundleId()).orElseThrow(CancellationException::new);
        if (!approval.id().equals(job.approvalId()) || approval.revokedAt() != null) throw new LibraryException(409, "승인이 철회 또는 교체되어 답변을 중단했습니다.");
        if (beforeDispatch && !Instant.now().isBefore(Instant.parse(approval.expiresAt()))) throw new LibraryException(409, "전송 전에 승인이 만료되었습니다.");
    }

    @Scheduled(fixedDelay = 500)
    public void cancelInvalidatedRequest() {
        AnswerJob observed;
        synchronized (this) {
            if (worker == null || cancelled) return;
            observed = active;
        }
        var approval = approvals.find(observed.bundleId()).orElse(null);
        boolean invalidated = store.find(observed.workspaceId(), observed.id()).isEmpty() || approval == null
                || !approval.id().equals(observed.approvalId()) || approval.revokedAt() != null;
        synchronized (this) {
            if (active == observed && worker != null && !cancelled && invalidated) stop();
        }
    }

    @PreDestroy
    public void close() {
        Thread pending;
        synchronized (this) {
            closing = true; pending = worker;
            if (worker != null) stop();
        }
        if (pending != null) {
            try { pending.join(5000); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }
    private void requireWorkspace(String workspaceId) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
    }
}
