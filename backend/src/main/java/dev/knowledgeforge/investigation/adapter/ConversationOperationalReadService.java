package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.attachment.domain.QuestionAttachment;
import dev.knowledgeforge.attachment.application.AttachmentService;
import dev.knowledgeforge.investigation.application.ConversationOperationalScopeAccess;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationOperationalReadService {
    public record Input(String scopeId, long scopeRevision) {}
    public record Start(String requestId, String digest, boolean approved) {}
    public record Preview(String id, String workspaceId, String threadId, String parentBundleId,
                          String scopeId, long scopeRevision, OperationalTool tool, String digest,
                          String queryPreview, String expiresAt, int maxRows, int maxBytes,
                          int timeoutSeconds, boolean sessionOnly) {}
    public record Result(String id, String workspaceId, String threadId, String parentBundleId,
                         OperationalTool tool, String status, String output, int rows, boolean truncated,
                         String capturedAt, boolean sessionOnly) {}
    public record CapturePreview(String id, String threadId, String parentBundleId, String digest,
                                 String text, int bytes, boolean truncated, String expiresAt, boolean sessionOnly) {}
    public record CaptureReceipt(String id, String threadId, String parentBundleId, String status,
                                 QuestionAttachment.Summary attachment, String contentHash, int externalCalls) {}

    private final ConversationOperationalScopeAccess scopes;
    private final MysqlInvestigationService mysql;
    private final DatadogInvestigationService datadog;
    private final OperationalCaptureService captures;
    private final AttachmentService attachments;
    private final Clock clock;
    private final ConversationAuthorityChecks authorityChecks = new ConversationAuthorityChecks();
    private final JsonMapper json = new JsonMapper();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<ScopeKey, Object> preparing = new HashMap<>();
    private final Map<String, CommittedCapture> committedCaptures = new HashMap<>();
    private record ScopeKey(String workspaceId, String scopeId, long revision) {}
    private record CommittedCapture(String workspaceId, CaptureReceipt receipt) {}

    public ConversationOperationalReadService(ConversationOperationalScopeAccess scopes, MysqlInvestigationService mysql,
                                               DatadogInvestigationService datadog, OperationalCaptureService captures,
                                               AttachmentService attachments, Clock clock) {
        this.scopes = scopes; this.mysql = mysql; this.datadog = datadog; this.captures = captures;
        this.attachments = attachments; this.clock = clock;
    }

    public Preview preview(String workspaceId, Input input) {
        var budget = budget();
        if (input == null || input.scopeRevision() < 1) throw invalid();
        identifier(input.scopeId());
        var scope = authorityChecks.call(budget, () -> scopes.requireCurrent(workspaceId, input.scopeId()));
        requireScope(scope, workspaceId, input);
        var key = new ScopeKey(workspaceId, scope.id(), scope.revision());
        var reservation = new Object();
        Pending existing;
        synchronized (pending) {
            pending.values().removeIf(value -> !value.running && !value.capturing && !value.snapshotting && expired(value.preview.expiresAt()));
            existing = pending.values().stream().filter(value -> value.preview.workspaceId().equals(workspaceId)
                    && value.scope.id().equals(input.scopeId()) && value.scope.revision() == input.scopeRevision()).findFirst().orElse(null);
            if (existing == null) {
                if (preparing.containsKey(key)) throw new LibraryException(409, "조회 범위를 확인 중입니다. 잠시 후 다시 확인하세요.");
                if (pending.size() + preparing.size() >= 32) throw new LibraryException(429, "조회 미리보기가 많습니다. 기존 조회를 마친 뒤 다시 시도하세요.");
                preparing.put(key, reservation);
            }
        }
        if (existing != null) {
            current(existing, budget);
            synchronized (pending) { requireIdentity(existing); return existing.preview; }
        }
        Pending operation = null;
        try {
            String underlyingId;
            String queryPreview;
            String expiresAt;
            int maxRows;
            int maxBytes;
            int timeout;
            if (scope.candidate() instanceof PendingOperationalScope.Mysql candidate) {
                if (candidate.equalities().isEmpty() || candidate.limit() > 50) throw incomplete();
                var query = new MysqlReadClient.Request(candidate.table(), candidate.columns(), candidate.equalities(), candidate.limit());
                var preview = mysql.preview(workspaceId, query);
                underlyingId = preview.id();
                queryPreview = "MySQL · " + preview.database() + " / " + query.table() + "\n조건: "
                        + query.equalities().entrySet().stream().map(entry -> entry.getKey() + " = " + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(", "))
                        + "\n가져올 항목: " + String.join(", ", query.columns()) + "\n최대 " + query.limit() + "행";
                expiresAt = preview.expiresAt(); maxRows = query.limit(); maxBytes = preview.maxBytes(); timeout = preview.timeoutSeconds();
            } else if (scope.candidate() instanceof PendingOperationalScope.Datadog candidate) {
                var query = new DatadogInvestigationService.Query(scope.binding().connectionRevision(), candidate.environment(),
                        candidate.service(), candidate.indexes(), candidate.from(), candidate.to(), candidate.limit());
                var preview = datadog.preview(workspaceId, query);
                underlyingId = preview.id();
                queryPreview = "Datadog · " + candidate.environment() + " / " + candidate.service()
                        + "\n기간(UTC): " + candidate.from() + " ~ " + candidate.to()
                        + "\n사용자 시간대: " + candidate.timeZone() + "\n인덱스: "
                        + String.join(", ", candidate.indexes().stream().sorted().toList()) + "\n최대 " + candidate.limit() + "개 로그";
                expiresAt = preview.expiresAt(); maxRows = query.limit(); maxBytes = preview.maxBytes(); timeout = preview.timeoutSeconds();
            } else throw incomplete();
            String id = UUID.randomUUID().toString();
            var binding = scope.binding();
            if (scope.expiresAt().isBefore(Instant.parse(expiresAt))) expiresAt = scope.expiresAt().toString();
            String digest = ContentDigest.of("CONVERSATION_OPERATIONAL_READ_V1\n" + id + "\n"
                    + json.writeValueAsString(scope) + "\n" + queryPreview + "\n" + expiresAt
                    + "\n" + maxRows + "\n" + maxBytes + "\n" + timeout);
            var preview = new Preview(id, workspaceId, binding.threadId(), binding.parentBundleId(), scope.id(), scope.revision(),
                    binding.tool(), digest, queryPreview, expiresAt, maxRows, maxBytes, timeout, true);
            operation = new Pending(scope, preview, underlyingId);
            current(operation, budget);
            synchronized (pending) {
                if (preparing.get(key) != reservation) throw stale();
                pending.put(id, operation);
            }
            return preview;
        } catch (RuntimeException failure) {
            if (operation != null) cancelUnderlying(operation);
            throw failure;
        } finally {
            synchronized (pending) { preparing.remove(key, reservation); }
        }
    }

    public Result execute(String workspaceId, String id, Start input) {
        var budget = budget();
        requireApproval(input);
        var operation = owned(workspaceId, id);
        current(operation, budget);
        synchronized (pending) {
            requireIdentity(operation);
            if (!operation.preview.digest().equals(input.digest())) throw stale();
            if (operation.requestId != null) {
                if (!operation.requestId.equals(input.requestId())) throw stale();
                return result(operation);
            }
            if (operation.cancelled) throw stale();
            for (var value : pending.values()) {
                if (value != operation && input.requestId().equals(value.requestId)
                        && value.preview.workspaceId().equals(workspaceId)) throw stale();
                if (value.running && value.preview.workspaceId().equals(workspaceId)
                        && value.preview.threadId().equals(operation.preview.threadId())) throw stale();
            }
            operation.requestId = input.requestId();
            operation.running = true;
        }
        Result observed;
        try {
            current(operation, budget);
            if (operation.cancelled) throw stale();
            if (operation.preview.tool() == OperationalTool.MYSQL_READ) {
                var value = mysql.execute(workspaceId, operation.underlyingId, input.requestId(), true,
                        () -> current(operation, budget));
                observed = state(operation, value.status(), value.output(), value.rows(), value.truncated(), value.capturedAt());
            } else {
                var value = datadog.execute(workspaceId, operation.underlyingId, input.requestId(), true,
                        () -> current(operation, budget));
                observed = state(operation, "OK".equals(value.status()) ? "SUCCEEDED" : value.status(),
                        value.output(), value.rows(), value.truncated(), value.capturedAt());
            }
            current(operation, budget);
        } catch (RuntimeException failure) {
            observed = state(operation, failure instanceof LibraryException ? "DENIED" : "FAILED");
        }
        synchronized (pending) {
            operation.result = operation.cancelled ? state(operation, "CANCELLED") : observed;
            operation.running = false;
            return operation.result;
        }
    }

    public Result get(String workspaceId, String id) {
        var operation = owned(workspaceId, id);
        current(operation, budget());
        synchronized (pending) { requireIdentity(operation); return result(operation); }
    }

    public Result cancel(String workspaceId, String id) {
        var operation = owned(workspaceId, id);
        Result terminal;
        synchronized (pending) {
            requireIdentity(operation);
            terminal = operation.result;
            if (terminal == null) operation.cancelled = true;
        }
        if (terminal != null) { current(operation, budget()); return terminal; }
        cancelUnderlying(operation);
        synchronized (pending) {
            if (!operation.running) operation.result = state(operation, "CANCELLED");
            return result(operation);
        }
    }

    public CapturePreview capturePreview(String workspaceId, String id) {
        var budget = budget();
        var operation = owned(workspaceId, id);
        current(operation, budget);
        synchronized (pending) {
            requireIdentity(operation);
            requireSuccess(operation);
            if (operation.capturePreview != null) {
                if (expired(operation.capturePreview.expiresAt())) throw stale();
                return operation.capturePreview;
            }
            if (operation.snapshotting) throw new LibraryException(409, "조회 결과를 확인 중입니다. 잠시 후 다시 확인하세요.");
            operation.snapshotting = true;
        }
        try {
            var snapshot = captures.preview(workspaceId, new OperationalCaptureService.Input(provider(operation), operation.underlyingId));
            current(operation, budget);
            String digest = ContentDigest.of("CONVERSATION_OPERATIONAL_CAPTURE_V1\n" + operation.preview.digest() + "\n" + snapshot.digest());
            var preview = new CapturePreview(id, operation.preview.threadId(), operation.preview.parentBundleId(), digest,
                    snapshot.text(), snapshot.bytes(), snapshot.truncated(), snapshot.expiresAt(), true);
            synchronized (pending) {
                requireIdentity(operation);
                requireSuccess(operation);
                operation.snapshot = snapshot;
                operation.capturePreview = preview;
            }
            return preview;
        } finally {
            synchronized (pending) { operation.snapshotting = false; }
        }
    }

    public CaptureReceipt capture(String workspaceId, String id, Start input) {
        var budget = budget();
        requireApproval(input);
        var operation = owned(workspaceId, id);
        current(operation, budget);
        synchronized (pending) {
            requireIdentity(operation);
            requireSuccess(operation);
            if (operation.capturePreview == null || expired(operation.capturePreview.expiresAt())
                    || !operation.capturePreview.digest().equals(input.digest())) throw stale();
            if (operation.captureRequestId != null && !operation.captureRequestId.equals(input.requestId())) throw stale();
            if (operation.capturing) throw new LibraryException(409, "결과를 저장 중입니다. 같은 요청으로 확인하세요.");
            if (!committedCaptures.containsKey(id) && committedCaptures.size()
                    + pending.values().stream().filter(value -> value.capturing).count() >= 64) {
                throw new LibraryException(429, "이번 실행의 저장 영수증 한도에 도달했습니다. 기존 결과를 먼저 확인하세요.");
            }
            operation.captureRequestId = input.requestId();
            operation.capturing = true;
        }
        try {
            current(operation, budget);
            var receipt = captures.capture(workspaceId, operation.snapshot.id(),
                    new OperationalCaptureService.Start(input.requestId(), operation.snapshot.digest(), true));
            var response = new CaptureReceipt(id, operation.preview.threadId(), operation.preview.parentBundleId(), receipt.status(),
                    receipt.attachment(), receipt.contentHash(), receipt.externalCalls());
            if ("CAPTURED".equals(response.status()) && response.attachment() != null) {
                synchronized (pending) { committedCaptures.put(id, new CommittedCapture(workspaceId, response)); }
            }
            current(operation, budget);
            return response;
        } finally {
            synchronized (pending) { operation.capturing = false; }
        }
    }

    public CaptureReceipt captureReceipt(String workspaceId, String id) {
        identifier(id);
        CommittedCapture committed;
        synchronized (pending) { committed = committedCaptures.get(id); }
        if (committed == null || !committed.workspaceId().equals(workspaceId)) {
            throw new LibraryException(404, "저장된 영수증이 없거나 앱을 다시 시작했습니다.");
        }
        var receipt = committed.receipt();
        var saved = authorityChecks.call(budget(), () -> attachments.get(workspaceId, receipt.attachment().id()));
        if (!workspaceId.equals(saved.workspaceId()) || !saved.contentHash().equals(receipt.contentHash())
                || !saved.summary().equals(receipt.attachment())) {
            throw new LibraryException(409, "저장된 첨부가 변경되었습니다. 결과를 다시 확인하세요.");
        }
        return receipt;
    }

    private void requireScope(PendingOperationalScope scope, String workspaceId, Input input) {
        if (scope == null || !scope.id().equals(input.scopeId()) || scope.revision() != input.scopeRevision()
                || !scope.binding().workspaceId().equals(workspaceId) || scope.expiredAt(clock.instant())) throw stale();
        if (scope.state() != PendingOperationalScope.State.FIELDS_COMPLETE) throw incomplete();
    }

    private void current(Pending operation, ConversationAuthorityChecks.Budget budget) {
        var scope = authorityChecks.call(budget, () -> scopes.requireCurrent(operation.preview.workspaceId(), operation.scope.id()));
        requireScope(scope, operation.preview.workspaceId(), new Input(operation.scope.id(), operation.scope.revision()));
        if (!scope.equals(operation.scope) || expired(operation.preview.expiresAt())) throw stale();
    }

    private ConversationAuthorityChecks.Budget budget() {
        return new ConversationAuthorityChecks.Budget(Duration.ofSeconds(2), Duration.ofSeconds(15));
    }

    @jakarta.annotation.PreDestroy
    public void close() { authorityChecks.close(); }

    private Pending owned(String workspaceId, String id) {
        identifier(id);
        synchronized (pending) {
            var operation = pending.get(id);
            if (operation == null || !operation.preview.workspaceId().equals(workspaceId)) {
                throw new LibraryException(404, "대화 조회가 없거나 앱을 다시 시작했습니다. 범위를 다시 확인하세요.");
            }
            return operation;
        }
    }

    private void requireIdentity(Pending operation) {
        if (pending.get(operation.preview.id()) != operation) throw stale();
    }

    private void cancelUnderlying(Pending operation) {
        if (operation.preview.tool() == OperationalTool.MYSQL_READ) mysql.cancel(operation.preview.workspaceId(), operation.underlyingId);
        else datadog.cancel(operation.preview.workspaceId(), operation.underlyingId);
    }

    private OperationalCaptureService.Provider provider(Pending operation) {
        return operation.preview.tool() == OperationalTool.MYSQL_READ ? OperationalCaptureService.Provider.MYSQL : OperationalCaptureService.Provider.DATADOG;
    }

    private Result result(Pending operation) {
        return operation.result != null ? operation.result : state(operation,
                operation.running ? operation.cancelled ? "CANCELLING" : "RUNNING" : operation.cancelled ? "CANCELLED" : "PREVIEW");
    }

    private Result state(Pending operation, String status) { return state(operation, status, "", 0, false, null); }

    private Result state(Pending operation, String status, String output, int rows, boolean truncated, String capturedAt) {
        if (!Set.of("SUCCEEDED", "EMPTY").contains(status)) { output = ""; rows = 0; truncated = false; }
        return new Result(operation.preview.id(), operation.preview.workspaceId(), operation.preview.threadId(),
                operation.preview.parentBundleId(), operation.preview.tool(), status, output, rows, truncated, capturedAt, true);
    }

    private void requireSuccess(Pending operation) {
        if (operation.result == null || !"SUCCEEDED".equals(operation.result.status()) || operation.cancelled) {
            throw new LibraryException(409, "성공한 조회 결과만 대화 첨부로 저장할 수 있습니다.");
        }
    }

    private boolean expired(String expiry) { return !clock.instant().isBefore(Instant.parse(expiry)); }
    private static void requireApproval(Start input) {
        if (input == null || !input.approved()) throw new LibraryException(403, "표시된 범위와 작업에 명시적으로 동의하세요.");
        if (input.requestId() == null || !input.requestId().matches("[A-Za-z0-9_-]{16,64}")
                || input.digest() == null || !input.digest().matches("[a-f0-9]{64}")) throw invalid();
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
    }
    private static LibraryException invalid() { return new LibraryException(400, "대화 조회 요청 형식을 확인하세요."); }
    private static LibraryException stale() { return new LibraryException(409, "대화·조회 범위·연결 또는 동의가 변경되었습니다. 다시 확인하세요."); }
    private static LibraryException incomplete() { return new LibraryException(409, "조회 범위가 아직 완성되지 않았습니다. 대화에서 필요한 내용을 확인하세요."); }

    private static final class Pending {
        final PendingOperationalScope scope;
        final Preview preview;
        final String underlyingId;
        String requestId;
        boolean running;
        volatile boolean cancelled;
        Result result;
        OperationalCaptureService.Preview snapshot;
        CapturePreview capturePreview;
        boolean snapshotting;
        String captureRequestId;
        boolean capturing;
        Pending(PendingOperationalScope scope, Preview preview, String underlyingId) {
            this.scope = scope; this.preview = preview; this.underlyingId = underlyingId;
        }
    }
}
