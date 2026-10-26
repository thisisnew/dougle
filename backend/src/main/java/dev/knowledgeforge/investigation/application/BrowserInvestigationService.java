package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.domain.ContentDigest;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BrowserInvestigationService {
    public record Request(String bundleId, String url, List<String> allowedGetPaths) {}
    public record Start(String requestId, String bundleId, String digest, boolean approved) {}
    public record Limits(int maxSeconds, int maxRequests, int maxResponseBytes, int maxTotalBytes, int maxTextCharacters) {}
    public record Preview(String bundleId, String digest, BrowserCaptureScope scope, String expiresAt, int maxAiCalls, Limits limits) {}
    private final BrowserInvestigationStore store;
    private final BrowserInvestigationTools tools;
    private final BrowserCaptureTransport transport;
    private final EvidenceBundleService bundles;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();
    private Thread worker;
    private ToolExecution.Cancellation cancellation;
    private boolean closing;

    public BrowserInvestigationService(BrowserInvestigationStore store, BrowserInvestigationTools tools,
                                      BrowserCaptureTransport transport, EvidenceBundleService bundles, PlatformTransactionManager manager) {
        this.store = store; this.tools = tools; this.transport = transport; this.bundles = bundles;
        this.transaction = new TransactionTemplate(manager);
    }

    public BrowserCaptureTransport.Capability capability() {
        if (!store.available()) return new BrowserCaptureTransport.Capability("SETUP_REQUIRED", "브라우저 조사 저장소 업데이트가 필요합니다. 최신 앱을 설치해 주세요.");
        return transport.capability();
    }

    public synchronized Preview preview(String workspace, Request request) {
        ready();
        if (request == null) throw new LibraryException(400, "브라우저 조사 범위를 입력해 주세요.");
        BrowserCaptureScope scope;
        try { scope = new BrowserCaptureScope(request.url(), request.allowedGetPaths()); }
        catch (IllegalArgumentException invalid) { throw new LibraryException(400, "공개 HTTPS 화면과 정확한 GET 경로를 최대 32개 입력해 주세요. 로그인 정보·쿼리·포트·인코딩 경로는 사용할 수 없습니다."); }
        return transaction.execute(status -> {
            var bundle = bundles.get(workspace, request.bundleId());
            String now = Instant.now().toString();
            String expires = Instant.now().plusSeconds(600).toString();
            String digest = ContentDigest.of("ISOLATED_BROWSER_V1\n" + workspace + "\n" + bundle.id() + "\n" + bundle.digest()
                    + "\n" + json.writeValueAsString(scope) + "\n" + json.writeValueAsString(limits()) + "\n" + UUID.randomUUID() + "\n" + expires);
            store.save(new BrowserInvestigationStore.Preview(digest, workspace, bundle.id(), bundle.digest(), scope, now, expires));
            return new Preview(bundle.id(), digest, scope, expires, 0, limits());
        });
    }

    public synchronized BrowserInvestigationStore.Job start(String workspace, Start request) {
        if (!store.available()) throw new LibraryException(503, "브라우저 조사 저장소 업데이트가 필요합니다.");
        if (request == null || !request.approved()) throw new LibraryException(403, "브라우저 접근 범위를 확인하고 실행에 동의해 주세요.");
        if (request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{16,64}")) throw new LibraryException(400, "잘못된 요청 식별자입니다.");
        var previous = store.find(workspace, request.requestId());
        if (previous.isPresent()) {
            var job = previous.get();
            if (!Objects.equals(job.digest(), request.digest()) || !Objects.equals(job.bundleId(), request.bundleId())) throw new LibraryException(409, "같은 요청 식별자를 다른 조사 범위에 사용할 수 없습니다.");
            return job;
        }
        ready();
        if (closing || worker != null) throw new LibraryException(409, "다른 브라우저 조사가 실행 또는 종료 중입니다.");
        var material = transaction.execute(status -> {
            var preview = store.preview(workspace, request.digest()).orElseThrow(() -> new LibraryException(409, "브라우저 접근 범위를 다시 확인해 주세요."));
            if (!Objects.equals(preview.bundleId(), request.bundleId()) || !Instant.now().isBefore(Instant.parse(preview.expiresAt()))) {
                throw new LibraryException(409, "브라우저 동의가 만료되었거나 대상이 변경되었습니다.");
            }
            if (!bundles.get(workspace, preview.bundleId()).digest().equals(preview.bundleDigest())) throw new LibraryException(409, "근거가 변경되었습니다. 다시 동의해 주세요.");
            if (!store.consume(workspace, request.digest(), request.requestId(), Instant.now().toString())) throw new LibraryException(409, "이미 사용한 동의입니다. 접근 범위를 다시 확인해 주세요.");
            return preview;
        });
        var job = get(workspace, request.requestId());
        cancellation = new ToolExecution.Cancellation();
        worker = Thread.ofVirtual().name("dougle-browser-capture").unstarted(() -> run(job, material.scope()));
        try { worker.start(); }
        catch (RuntimeException failure) {
            store.update(workspace, job.id(), "FAILED", null, "브라우저 실행기를 시작하지 못했습니다. 다시 동의해 주세요.");
            worker = null;
            throw new LibraryException(503, "브라우저 실행기를 시작하지 못했습니다.");
        }
        return job;
    }

    public BrowserInvestigationStore.Job get(String workspace, String id) {
        return store.find(workspace, id).orElseThrow(() -> new LibraryException(404, "브라우저 조사 작업을 찾을 수 없습니다."));
    }

    public List<BrowserInvestigationStore.Job> list(String workspace, String bundleId) {
        bundles.get(workspace, bundleId);
        return store.list(workspace, bundleId);
    }

    public synchronized BrowserInvestigationStore.Job cancel(String workspace, String id) {
        var job = get(workspace, id);
        if (job.active()) {
            store.update(workspace, id, "CANCELLING", null, null);
            cancellation.cancel();
            if (worker != null) worker.interrupt();
        }
        return get(workspace, id);
    }

    private void run(BrowserInvestigationStore.Job job, BrowserCaptureScope scope) {
        try {
            synchronized (this) {
                if (cancellation.isCancelled()) return;
                if (!store.update(job.workspaceId(), job.id(), "RUNNING", null, null)) return;
            }
            try (var executor = tools.open(job.workspaceId(), scope, cancellation)) {
                var result = executor.execute(new ToolExecution.Invocation<>(BrowserInvestigationTools.TOOL_ID,
                        BrowserInvestigationTools.binding(job.workspaceId(), scope), scope));
                synchronized (this) {
                    if (cancellation.isCancelled() || closing) return;
                    if (result.status() != ToolExecution.Status.OK || result.truncated()) {
                        store.update(job.workspaceId(), job.id(), "FAILED", null, "브라우저 조사가 실패했거나 제한을 초과했습니다. 범위를 다시 확인해 주세요.");
                    } else {
                        var snapshot = json.readValue(result.output(), BrowserCaptureTransport.Snapshot.class);
                        transaction.executeWithoutResult(status -> {
                            bundles.get(job.workspaceId(), job.bundleId());
                            store.update(job.workspaceId(), job.id(), "SUCCEEDED", snapshot, null);
                        });
                    }
                }
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (!cancellation.isCancelled()) store.update(job.workspaceId(), job.id(), "FAILED", null, "브라우저 조사를 완료하지 못했습니다. 설치 상태와 접근 범위를 확인해 주세요.");
            }
        } finally {
            synchronized (this) {
                try {
                    if (cancellation.isCancelled()) store.update(job.workspaceId(), job.id(), "CANCELLED", null, null);
                } finally { worker = null; }
            }
        }
    }

    private void ready() {
        if (!"AVAILABLE".equals(capability().status())) throw new LibraryException(503, capability().message());
    }

    private static Limits limits() {
        return new Limits(BrowserCaptureTransport.MAX_SECONDS, BrowserCaptureTransport.MAX_REQUESTS,
                BrowserCaptureTransport.MAX_RESPONSE_BYTES, BrowserCaptureTransport.MAX_TOTAL_BYTES, BrowserCaptureTransport.MAX_TEXT_CHARACTERS);
    }

    @PreDestroy
    public synchronized void close() {
        closing = true;
        if (cancellation != null) cancellation.cancel();
        if (worker != null) worker.interrupt();
    }
}
