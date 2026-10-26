package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.investigation.domain.PlanDecision;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.knowledge.application.ProviderExecutionException;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvestigationPlanService implements ConversationOperationalScopeAccess {
    public record PreviewRequest(String bundleId, String provider, String requestedModel) {}
    public record Preview(String bundleId, String provider, String requestedModel, String digest, String prompt,
                          int characters, int maxCalls, int maxToolCalls, String expiresAt) {}
    public record Start(String requestId, String bundleId, String provider, String requestedModel, String digest, boolean approved) {}
    private final InvestigationPlanStore store;
    private final InvestigationPlanPreview previews;
    private final EvidenceBundleService bundles;
    private final ChatThreadService threads;
    private final PlanModel model;
    private final LocalInvestigationTools tools;
    private final PlanDecisionParser parser;
    private final OperationalScopeClarification scopes;
    private final TransactionTemplate transaction;
    private Thread worker;
    private InvestigationPlanStore.Job active;
    private ToolExecution.Cancellation cancellation;
    private long started;
    private boolean closing;
    private boolean timedOut;

    public InvestigationPlanService(InvestigationPlanStore store, InvestigationPlanPreview previews,
                                   EvidenceBundleService bundles, ChatThreadService threads, PlanModel model,
                                   LocalInvestigationTools tools, PlanDecisionParser parser, PlatformTransactionManager manager,
                                   OperationalScopeClarification.Authority authority) {
        this.store = store; this.previews = previews; this.bundles = bundles; this.threads = threads;
        this.model = model; this.tools = tools; this.parser = parser; this.transaction = new TransactionTemplate(manager);
        this.scopes = new OperationalScopeClarification(authority, java.time.Clock.systemUTC());
    }

    public synchronized Preview preview(String workspaceId, PreviewRequest request) {
        if (request == null) throw new LibraryException(400, "검색 계획 요청이 필요합니다.");
        return transaction.execute(status -> {
            var material = previews.prepare(workspaceId, request.bundleId(), request.provider(), request.requestedModel());
            Instant now = Instant.now();
            String expires = now.plus(Duration.ofMinutes(10)).toString();
            String digest = ContentDigest.of(material.bindingDigest() + "\n" + UUID.randomUUID() + "\n" + expires);
            store.save(new InvestigationPlanStore.Preview(workspaceId, request.bundleId(), material.provider(), material.requestedModel(),
                    digest, material.bindingDigest(), material.prompt(), now.toString(), expires));
            return new Preview(request.bundleId(), material.provider(), material.requestedModel(), digest, material.prompt(),
                    material.prompt().length(), 1, 1, expires);
        });
    }

    public synchronized InvestigationPlanStore.Job start(String workspaceId, Start request) {
        if (request == null || !request.approved()) throw new LibraryException(403, "계획 AI 1회와 로컬 검색 최대 1회 또는 수동 조회 제안에 동의하세요. 운영 조회는 실행하지 않습니다.");
        if (request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{16,64}")) throw new LibraryException(400, "잘못된 요청 식별자입니다.");
        var previous = store.find(workspaceId, request.requestId());
        if (previous.isPresent()) {
            var job = previous.get();
            if (!Objects.equals(job.bundleId(), request.bundleId()) || !Objects.equals(job.digest(), request.digest())
                    || !Objects.equals(job.provider(), request.provider()) || !Objects.equals(job.requestedModel(), request.requestedModel())) {
                throw new LibraryException(409, "같은 요청 식별자를 다른 검색 계획에 사용할 수 없습니다.");
            }
            return job;
        }
        if (closing) throw new LibraryException(503, "앱이 종료 중입니다.");
        if (worker != null) throw new LibraryException(409, "다른 검색 계획이 실행 또는 취소 중입니다.");
        var job = transaction.execute(status -> {
            var preview = store.preview(workspaceId, request.digest()).orElseThrow(() -> new LibraryException(409, "새 검색 계획 미리보기가 필요합니다."));
            if (!Objects.equals(preview.bundleId(), request.bundleId()) || !Objects.equals(preview.provider(), request.provider())
                    || !Objects.equals(preview.requestedModel(), request.requestedModel())) throw new LibraryException(409, "검색 계획 제공자·모델·근거가 변경되었습니다.");
            validate(preview);
            if (!store.consume(workspaceId, request.digest(), request.requestId(), Instant.now().toString())) {
                throw new LibraryException(409, "이미 사용한 검색 계획 동의입니다. 새 미리보기를 준비하세요.");
            }
            return get(workspaceId, request.requestId());
        });
        active = job; cancellation = new ToolExecution.Cancellation(); timedOut = false; started = System.nanoTime();
        worker = Thread.ofVirtual().name("forge-investigation-" + job.id()).unstarted(() -> run(job));
        try { worker.start(); }
        catch (RuntimeException exception) {
            store.update(workspaceId, job.id(), "FAILED", null, null, null, false, null, "검색 실행기를 시작하지 못했습니다. 새 동의가 필요합니다.");
            worker = null; active = null;
            throw new LibraryException(503, "검색 실행기를 시작하지 못했습니다.");
        }
        return job;
    }

    public InvestigationPlanStore.Job get(String workspaceId, String id) {
        return store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "검색 계획 작업을 찾을 수 없습니다."));
    }

    public List<InvestigationPlanStore.Job> list(String workspaceId, String bundleId) {
        bundles.get(workspaceId, bundleId);
        return store.list(workspaceId, bundleId);
    }

    public OperationalToolProposal proposal(String workspaceId, String id) {
        var job = get(workspaceId, id);
        bundles.get(workspaceId, job.bundleId());
        if (!"SUCCEEDED".equals(job.status())) throw new LibraryException(409, "완료된 수동 조회 제안만 확인할 수 있습니다.");
        PlanDecision.OperationalTool kind;
        if ("PROPOSE_MYSQL_READ".equals(job.decision())) kind = PlanDecision.OperationalTool.MYSQL_READ;
        else if ("PROPOSE_DATADOG_LOGS".equals(job.decision())) kind = PlanDecision.OperationalTool.DATADOG_LOGS;
        else throw new LibraryException(409, "수동 운영 조회 제안이 아닙니다.");
        return OperationalToolProposal.from(job, kind);
    }

    public OperationalClarification clarification(String workspaceId, String id) {
        return OperationalClarification.from(id, scope(workspaceId, id));
    }

    public PendingOperationalScope scope(String workspaceId, String id) {
        var job = get(workspaceId, id);
        bundles.get(workspaceId, job.bundleId());
        if (!"SUCCEEDED".equals(job.status()) || !"PENDING_SCOPE".equals(job.toolStatus())
                || !("PROPOSE_MYSQL_READ".equals(job.decision()) || "PROPOSE_DATADOG_LOGS".equals(job.decision()))) {
            throw new LibraryException(409, "대화에서 확인할 조회 범위 후보가 없습니다. 새 계획을 준비하세요.");
        }
        var scope = scopes.get(workspaceId, job.output());
        if (!scope.binding().parentBundleId().equals(job.bundleId())
                || !job.decision().equals("PROPOSE_" + scope.binding().tool().name())) {
            throw new LibraryException(409, "조회 범위 후보가 변경되었습니다. 새 계획을 준비하세요.");
        }
        return scope;
    }

    @Override
    public PendingOperationalScope requireCurrent(String workspaceId, String scopeId) {
        var scope = scopes.get(workspaceId, scopeId);
        boolean published = store.list(workspaceId, scope.binding().parentBundleId()).stream().anyMatch(job ->
                "SUCCEEDED".equals(job.status()) && "PENDING_SCOPE".equals(job.toolStatus())
                        && scopeId.equals(job.output()) && job.decision().equals("PROPOSE_" + scope.binding().tool().name()));
        if (!published) throw new LibraryException(404, "완료된 계획의 조회 범위 후보를 찾을 수 없습니다.");
        return scope;
    }

    public synchronized InvestigationPlanStore.Job cancel(String workspaceId, String id) {
        var job = get(workspaceId, id);
        if (job.active() && active != null && active.id().equals(id) && active.workspaceId().equals(workspaceId)) stop(false);
        return get(workspaceId, id);
    }

    public void cancelScope(String workspaceId, String id, long expectedRevision) {
        if (expectedRevision < 1) throw new LibraryException(400, "취소할 조회 범위 revision이 필요합니다.");
        var scope = scope(workspaceId, id);
        scopes.cancel(workspaceId, scope.id(), expectedRevision);
    }

    public EvidenceBundle capture(String workspaceId, String id, String requestId) {
        return transaction.execute(status -> {
            var job = get(workspaceId, id);
            if (!job.status().equals("SUCCEEDED") || !"SEARCH".equals(job.decision()) || !"OK".equals(job.toolStatus())) {
                throw new LibraryException(409, "성공한 로컬 검색 결과만 새 근거로 고정할 수 있습니다.");
            }
            var source = bundles.get(workspaceId, job.bundleId());
            String threadId = source.payload().chatContext() == null ? source.id() : source.payload().chatContext().threadId();
            var snapshot = tools.snapshot(workspaceId, job.id(), job.query());
            return threads.createFromSnapshot(workspaceId,
                    new ChatThreadService.Create(requestId, threadId, source.id(), source.payload().question(), List.of()), snapshot);
        });
    }

    private void validate(InvestigationPlanStore.Preview preview) {
        if (!Instant.now().isBefore(Instant.parse(preview.expiresAt()))) throw new LibraryException(409, "검색 계획 동의가 만료되었습니다.");
        var current = previews.prepare(preview.workspaceId(), preview.bundleId(), preview.provider(), preview.requestedModel());
        if (!current.bindingDigest().equals(preview.bindingDigest()) || !current.prompt().equals(preview.prompt())) {
            throw new LibraryException(409, "검색 계획 근거·문맥·모델 연결이 변경되었습니다. 새 동의가 필요합니다.");
        }
    }

    private void check(InvestigationPlanStore.Job job) {
        if (active != job || cancellation.isCancelled() || Thread.currentThread().isInterrupted() || closing
                || System.nanoTime() - started >= Duration.ofSeconds(InvestigationPlanPreview.MAX_SECONDS).toNanos()) throw new CancellationException();
        var current = get(job.workspaceId(), job.id());
        if (!current.active() || current.status().equals("CANCELLING")) throw new CancellationException();
        validate(store.preview(job.workspaceId(), job.digest()).orElseThrow(CancellationException::new));
    }

    private void run(InvestigationPlanStore.Job job) {
        var proposed = new java.util.concurrent.atomic.AtomicReference<PendingOperationalScope>();
        boolean published = false;
        try {
            String prompt;
            synchronized (this) {
                prompt = transaction.execute(status -> {
                    check(job);
                    if (!store.update(job.workspaceId(), job.id(), "RUNNING", null, null, null, false, null, null)) throw new CancellationException();
                    return store.preview(job.workspaceId(), job.digest()).orElseThrow().prompt();
                });
            }
            String raw = model.generate(job.provider(), prompt, job.requestedModel());
            var decision = parser.parse(raw);
            String decisionName = decision instanceof PlanDecision.Search ? "SEARCH"
                    : decision instanceof PlanDecision.Propose proposal ? "PROPOSE_" + proposal.tool().name() : "STOP";
            ToolExecution.Result result = null;
            String query = null;
            synchronized (this) { check(job); }
            if (decision instanceof PlanDecision.Search search) {
                query = search.query();
                var budget = new ToolExecution.Budget(1, Duration.ofSeconds(10), Duration.ofSeconds(10),
                        InvestigationPlanPreview.MAX_OUTPUT_CHARACTERS, InvestigationPlanPreview.MAX_OUTPUT_CHARACTERS);
                result = tools.search(job.workspaceId(), job.id(), query, budget, cancellation);
            }
            synchronized (this) {
                ToolExecution.Result completed = result;
                String selectedQuery = query;
                transaction.executeWithoutResult(status -> {
                    check(job);
                    if (decision instanceof PlanDecision.Propose proposal && proposal.candidate() != null) {
                        var source = bundles.get(job.workspaceId(), job.bundleId());
                        String threadId = source.payload().chatContext() == null ? source.id() : source.payload().chatContext().threadId();
                        var scope = scopes.open(job.workspaceId(), threadId, source.id(), proposal.candidate(), PendingOperationalScope.Origin.MODEL_PROPOSAL);
                        proposed.set(scope);
                        scopes.get(job.workspaceId(), scope.id());
                        check(job);
                        if (!store.update(job.workspaceId(), job.id(), "SUCCEEDED", decisionName, null,
                                scope.id(), false, "PENDING_SCOPE", null)) throw new CancellationException();
                        return;
                    }
                    boolean successful = completed == null || completed.status() == ToolExecution.Status.OK || completed.status() == ToolExecution.Status.EMPTY;
                    store.update(job.workspaceId(), job.id(), successful ? "SUCCEEDED" : "FAILED", decisionName, selectedQuery,
                            successful && completed != null ? completed.output() : null, completed != null && completed.truncated(),
                            completed == null ? null : completed.status().name(), successful ? null : "로컬 검색이 완료되지 않았습니다: " + completed.reason());
                });
                published = true;
            }
        } catch (Exception exception) {
            synchronized (this) {
                Thread.interrupted();
                boolean cancelled = cancellation.isCancelled() && !timedOut;
                String diagnostic = exception instanceof ProviderExecutionException execution ? " [" + execution.diagnostic().safeSummary() + "]" : "";
                String error = timedOut ? "검색 계획 시간 한도 90초를 초과했습니다. 새 동의가 필요합니다." + diagnostic
                        : exception instanceof LibraryException problem ? problem.getMessage() : "검색 계획 형식 또는 실행에 실패했습니다. 새 동의가 필요합니다.";
                store.update(job.workspaceId(), job.id(), cancelled ? "CANCELLED" : "FAILED", null, null, null, false, null, cancelled ? null : error);
            }
        } finally {
            if (!published && proposed.get() != null) {
                try { scopes.cancel(job.workspaceId(), proposed.get().id(), proposed.get().revision()); }
                catch (LibraryException alreadyInvalidated) { }
            }
            synchronized (this) { active = null; worker = null; cancellation = null; }
        }
    }

    private void stop(boolean timeout) {
        timedOut |= timeout;
        cancellation.cancel();
        store.update(active.workspaceId(), active.id(), "CANCELLING", null, null, null, false, null, null);
        worker.interrupt();
    }

    @Scheduled(fixedDelay = 200)
    public synchronized void monitor() {
        if (worker == null || cancellation.isCancelled()) return;
        if (System.nanoTime() - started >= Duration.ofSeconds(InvestigationPlanPreview.MAX_SECONDS).toNanos()) { stop(true); return; }
        if (store.find(active.workspaceId(), active.id()).isEmpty()) { stop(false); return; }
        var preview = store.preview(active.workspaceId(), active.digest()).orElse(null);
        if (preview == null || !Instant.now().isBefore(Instant.parse(preview.expiresAt()))) stop(false);
    }

    @PreDestroy
    public void close() {
        Thread pending;
        synchronized (this) { closing = true; pending = worker; if (worker != null) stop(false); }
        if (pending != null) {
            try { pending.join(5000); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }
}
