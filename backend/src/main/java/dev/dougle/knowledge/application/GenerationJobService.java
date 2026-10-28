package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.GenerationJob;
import dev.dougle.knowledge.domain.Knowledge.Document;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenerationJobService {
    public record Request(String requestId, String provider, int expectedVersion, String digest, boolean approved, String requestedModel) {
        public Request(String requestId, String provider, int expectedVersion, String digest, boolean approved) {
            this(requestId, provider, expectedVersion, digest, approved, null);
        }
    }
    private final GenerationJobStore store;
    private final CuratorService curator;
    private final KnowledgeService knowledge;
    private final LibraryStore library;
    private final CliDiagnostics diagnostics;
    private final TransactionTemplate transaction;
    private GenerationJob active;
    private Thread worker;
    private boolean cancelled;
    private boolean closing;

    public GenerationJobService(GenerationJobStore store, CuratorService curator, KnowledgeService knowledge,
                                LibraryStore library, PlatformTransactionManager manager, CliDiagnostics diagnostics) {
        this.store = store;
        this.curator = curator;
        this.knowledge = knowledge;
        this.library = library;
        this.diagnostics = diagnostics;
        this.transaction = new TransactionTemplate(manager);
    }

    public synchronized GenerationJob submit(String workspace, String card, Request request) {
        requireWorkspace(workspace);
        if (!request.approved()) throw new LibraryException(403, "외부 AI 전송을 승인하세요.");
        if (request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{16,64}")) {
            throw new LibraryException(400, "작업 요청 ID는 영문·숫자·하이픈·밑줄 16~64자여야 합니다.");
        }
        var previous = store.find(workspace, request.requestId());
        if (previous.isPresent()) {
            var job = previous.get();
            if (!job.sourceCardId().equals(card) || !job.provider().equals(request.provider())
                    || job.expectedVersion() != request.expectedVersion() || !job.digest().equals(request.digest())
                    || (request.requestedModel() != null
                        && !CuratorSettings.normalize(request.requestedModel()).equals(job.requestedModel()))) {
                throw new LibraryException(409, "같은 요청 ID를 다른 생성 내용에 사용할 수 없습니다.");
            }
            return job;
        }
        if (closing) throw new LibraryException(503, "앱이 종료 중입니다.");
        if (worker != null) throw new LibraryException(409, "다른 AI 작업이 실행 또는 취소 중입니다.");
        String now = Instant.now().toString();
        GenerationJob job = transaction.execute(status -> {
            String requestedModel = curator.checkRequest(workspace, card, request.provider(), request.expectedVersion(),
                    request.digest(), true, request.requestedModel());
            GenerationJob created = new GenerationJob(request.requestId(), workspace, card, request.provider(),
                    request.expectedVersion(), request.digest(), "QUEUED", null, null, now, now, requestedModel,
                    null, "NOT_CHECKED", null);
            store.create(created);
            return created;
        });
        active = job;
        cancelled = false;
        worker = Thread.ofVirtual().name("forge-generation-" + job.id()).unstarted(() -> run(job));
        try { worker.start(); }
        catch (RuntimeException exception) {
            active = null; worker = null;
            store.update(workspace, job.id(), "FAILED", null, "AI 작업 실행기를 시작할 수 없습니다.");
            throw new LibraryException(503, "AI 작업 실행기를 시작할 수 없습니다.");
        }
        return job;
    }

    public GenerationJob get(String workspace, String id) {
        return store.find(workspace, id).orElseThrow(() -> new LibraryException(404, "AI 작업을 찾을 수 없습니다."));
    }

    public List<GenerationJob> list(String workspace) {
        requireWorkspace(workspace);
        return store.list(workspace);
    }

    public synchronized GenerationJob cancel(String workspace, String id) {
        GenerationJob job = get(workspace, id);
        if (!job.active()) return job;
        if (!cancelled && active != null && active.workspaceId().equals(workspace) && active.id().equals(id)) {
            cancelled = true;
            store.update(workspace, id, "CANCELLING", null, null);
            worker.interrupt();
        }
        return get(workspace, id);
    }

    private void run(GenerationJob job) {
        try {
            synchronized (this) {
                if (cancelled) throw new CancellationException();
                if (!store.update(job.workspaceId(), job.id(), "RUNNING", null, null)) throw new CancellationException();
            }
            observeCli(job);
            curator.generate(job.workspaceId(), job.sourceCardId(), job.provider(), job.expectedVersion(), job.digest(), true,
                    job.requestedModel(), draft -> save(job, draft));
        } catch (Exception exception) {
            synchronized (this) {
                Thread.interrupted();
                String error = exception instanceof LibraryException problem ? problem.getMessage() : "AI 작업에 실패했습니다. 기존 지식은 유지됩니다.";
                store.update(job.workspaceId(), job.id(), cancelled ? "CANCELLED" : "FAILED", null, cancelled ? null : error);
            }
        } finally {
            synchronized (this) { active = null; worker = null; cancelled = false; }
        }
    }

    private void observeCli(GenerationJob job) {
        String version = null;
        String status = "UNAVAILABLE";
        try {
            var observed = diagnostics.probe(CuratorModel.Provider.valueOf(job.provider()));
            if (observed != null && java.util.Set.of("AVAILABLE", "UNAVAILABLE", "UNRECOGNIZED").contains(observed.status())) {
                status = observed.status();
                if (status.equals("AVAILABLE")) {
                    String candidate = observed.version();
                    if (candidate != null && candidate.length() <= 128
                            && candidate.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) version = candidate;
                    else status = "UNRECOGNIZED";
                }
            }
        } catch (LibraryException exception) {
            status = exception.status() == 409 ? "BUSY" : "UNAVAILABLE";
        } catch (RuntimeException exception) {
            status = "UNAVAILABLE";
        }
        synchronized (this) {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!store.recordCli(job.workspaceId(), job.id(), version, status, Instant.now().toString())) {
                throw new LibraryException(409, "CLI 관측 이력을 저장하지 못해 생성을 중단했습니다.");
            }
        }
    }

    private synchronized Document save(GenerationJob job, CuratorService.Generated draft) {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
        return transaction.execute(status -> {
            Document result = knowledge.createGenerated(draft.source(), draft.title(), draft.markdown());
            if (!store.update(job.workspaceId(), job.id(), "SUCCEEDED", result.card().id(), null)) {
                throw new LibraryException(409, "작업 상태가 변경되어 결과를 저장하지 않았습니다.");
            }
            return result;
        });
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void cancelDeletedWorkspace() {
        if (!cancelled && active != null && store.find(active.workspaceId(), active.id()).isEmpty()) {
            cancelled = true;
            worker.interrupt();
        }
    }

    @PreDestroy
    public void close() {
        Thread pending;
        synchronized (this) {
            closing = true;
            pending = worker;
            if (pending != null) { cancelled = true; pending.interrupt(); }
        }
        if (pending != null) {
            try { pending.join(5000); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }

    private void requireWorkspace(String workspace) {
        library.workspace(workspace).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
    }
}
