package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.attachment.application.AttachmentService;
import dev.knowledgeforge.attachment.domain.QuestionAttachment;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OperationalCaptureService {
    public enum Provider { MYSQL, DATADOG }
    public record Input(Provider provider, String resultId) {}
    public record Preview(String id, String digest, String text, int bytes, boolean truncated, String expiresAt, boolean sessionOnly) {}
    public record Start(String requestId, String digest, boolean approved) {}
    public record Receipt(String id, String status, QuestionAttachment.Summary attachment, String contentHash, int externalCalls) {}
    private record Snapshot(String status, String output, int rows, boolean truncated, String capturedAt, OperationalResultText.Text provenance) {}

    private final MysqlInvestigationService mysql;
    private final DatadogInvestigationService datadog;
    private final AttachmentService attachments;
    private final LibraryStore library;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper json = new JsonMapper();
    private final Map<String, Pending> pending = new HashMap<>();

    public OperationalCaptureService(MysqlInvestigationService mysql, DatadogInvestigationService datadog,
                                     AttachmentService attachments, LibraryStore library, Clock clock,
                                     PlatformTransactionManager transactions) {
        this.mysql = mysql; this.datadog = datadog; this.attachments = attachments; this.library = library; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(10);
    }

    public Preview preview(String workspaceId, Input input) {
        requireWorkspace(workspaceId);
        if (input == null || input.provider() == null || input.resultId() == null
                || !input.resultId().matches("[A-Za-z0-9_-]{1,64}")) throw new LibraryException(400, "저장할 조회 결과를 선택하세요.");
        Snapshot snapshot = snapshot(workspaceId, input);
        String sourceDigest = digest(snapshot);
        var rendered = OperationalResultText.snapshot(input.provider().name(), input.resultId(), snapshot.capturedAt(),
                snapshot.rows(), snapshot.output(), snapshot.truncated(), snapshot.provenance());
        String digest = ContentDigest.of("OPERATIONAL_CAPTURE_V1\n" + workspaceId + "\n" + input.provider() + "\n"
                + input.resultId() + "\n" + sourceDigest + "\n" + rendered.text());
        Instant now = clock.instant();
        synchronized (pending) {
            pending.values().removeIf(value -> !value.running && !now.isBefore(Instant.parse(value.preview.expiresAt())));
            for (var value : pending.values()) {
                if (value.workspaceId.equals(workspaceId) && value.input.equals(input)) {
                    if (!value.sourceDigest.equals(sourceDigest)) throw new LibraryException(409, "조회 결과가 변경되었습니다.");
                    return value.preview;
                }
            }
            if (pending.size() >= 32) throw new LibraryException(429, "저장 미리보기가 많습니다. 잠시 후 다시 시도하세요.");
            var preview = new Preview(UUID.randomUUID().toString(), digest, rendered.text(),
                    rendered.text().getBytes(StandardCharsets.UTF_8).length, rendered.truncated(), now.plus(Duration.ofMinutes(5)).toString(), true);
            pending.put(preview.id(), new Pending(workspaceId, input, preview, sourceDigest));
            return preview;
        }
    }

    public Receipt capture(String workspaceId, String id, Start input) {
        if (input == null || !input.approved()) throw new LibraryException(403, "표시한 내용을 로컬 첨부로 저장하는 데 동의하세요.");
        if (input.requestId() == null || !input.requestId().matches("[A-Za-z0-9_-]{16,64}")) throw new LibraryException(400, "요청 식별자를 확인하세요.");
        requireWorkspace(workspaceId);
        Pending operation;
        Receipt replay;
        synchronized (pending) {
            operation = owned(workspaceId, id);
            if (!operation.preview.digest().equals(input.digest())) throw new LibraryException(409, "저장할 내용이 변경되었습니다. 새 동의가 필요합니다.");
            if (pending.values().stream().anyMatch(value -> value.workspaceId.equals(workspaceId)
                    && input.requestId().equals(value.requestId) && value != operation)) {
                throw new LibraryException(409, "같은 요청 식별자를 다른 저장에 사용할 수 없습니다.");
            }
            if (operation.requestId != null) {
                if (!operation.requestId.equals(input.requestId())) throw new LibraryException(409, "이미 사용한 저장 동의입니다.");
                replay = receipt(operation);
            } else {
                if (pending.values().stream().anyMatch(value -> value.running)) throw new LibraryException(409, "다른 결과를 저장 중입니다.");
                operation.requestId = input.requestId();
                operation.running = true;
                replay = null;
            }
        }
        if (replay != null) return verifySaved(workspaceId, replay);
        Receipt saved;
        try {
            saved = transaction.execute(status -> {
                validateCurrent(operation);
                byte[] content = operation.preview.text().getBytes(StandardCharsets.UTF_8);
                String name = operation.input.provider().name().toLowerCase(java.util.Locale.ROOT) + "-capture-" + id + ".txt";
                var attachment = attachments.upload(workspaceId, name, content);
                var stored = attachments.get(workspaceId, attachment.id());
                if (!stored.text().equals(operation.preview.text()) || !stored.contentHash().equals(ContentDigest.of(content))) {
                    throw new LibraryException(409, "저장한 내용이 미리보기와 다릅니다.");
                }
                validateCurrent(operation);
                return new Receipt(id, "CAPTURED", attachment, stored.contentHash(), 0);
            });
            saved = Objects.requireNonNull(saved);
        } catch (RuntimeException failure) {
            saved = new Receipt(id, "FAILED", null, null, 0);
        }
        synchronized (pending) {
            operation.receipt = saved;
            operation.running = false;
        }
        return saved;
    }

    public Receipt get(String workspaceId, String id) {
        requireWorkspace(workspaceId);
        Receipt result;
        synchronized (pending) { result = receipt(owned(workspaceId, id)); }
        return verifySaved(workspaceId, result);
    }

    private Receipt verifySaved(String workspaceId, Receipt receipt) {
        if (receipt.attachment() != null) {
            var saved = attachments.get(workspaceId, receipt.attachment().id());
            if (!saved.contentHash().equals(receipt.contentHash()) || !ContentDigest.of(saved.text()).equals(receipt.contentHash())) {
                throw new LibraryException(409, "로컬 첨부가 변경되었습니다.");
            }
        }
        return receipt;
    }

    private void validateCurrent(Pending operation) {
        requireWorkspace(operation.workspaceId);
        String currentDigest = digest(snapshot(operation.workspaceId, operation.input));
        if (!clock.instant().isBefore(Instant.parse(operation.preview.expiresAt())) || !operation.sourceDigest.equals(currentDigest)) {
            throw new LibraryException(409, "조회 결과나 저장 동의가 만료 또는 변경되었습니다.");
        }
    }

    private Snapshot snapshot(String workspaceId, Input input) {
        Snapshot snapshot;
        if (input.provider() == Provider.MYSQL) {
            var result = mysql.result(workspaceId, input.resultId());
            snapshot = new Snapshot(result.status(), result.output(), result.rows(), result.truncated(), result.capturedAt(), mysql.provenance(workspaceId, input.resultId()));
        } else {
            var result = datadog.result(workspaceId, input.resultId());
            snapshot = new Snapshot(result.status(), result.output(), result.rows(), result.truncated(), result.capturedAt(), datadog.provenance(workspaceId, input.resultId()));
        }
        String success = input.provider() == Provider.MYSQL ? "SUCCEEDED" : "OK";
        if (snapshot.provenance() == null || !success.equals(snapshot.status()) || snapshot.output() == null || snapshot.output().isBlank()
                || snapshot.rows() < 0 || snapshot.rows() > 50 || snapshot.output().getBytes(StandardCharsets.UTF_8).length > 16384) {
            throw new LibraryException(409, "완료된 비어 있지 않은 조회 결과만 저장할 수 있습니다.");
        }
        try { Instant.parse(snapshot.capturedAt()); }
        catch (RuntimeException invalid) { throw new LibraryException(409, "조회 시각을 확인할 수 없습니다."); }
        return snapshot;
    }

    private String digest(Snapshot snapshot) { return ContentDigest.of(json.writeValueAsString(snapshot)); }
    private void requireWorkspace(String workspaceId) {
        if (workspaceId == null || library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
    }
    private Pending owned(String workspaceId, String id) {
        var value = pending.get(id);
        if (value == null || !value.workspaceId.equals(workspaceId)) throw new LibraryException(404, "저장 미리보기가 없거나 앱을 다시 시작했습니다.");
        if (!value.running && !clock.instant().isBefore(Instant.parse(value.preview.expiresAt()))) {
            pending.remove(id);
            throw new LibraryException(409, "저장 미리보기가 만료되었습니다. 새 동의가 필요합니다.");
        }
        return value;
    }
    private Receipt receipt(Pending value) {
        return value.receipt != null ? value.receipt : new Receipt(value.preview.id(), value.running ? "RUNNING" : "PREVIEW", null, null, 0);
    }
    private static final class Pending {
        final String workspaceId;
        final Input input;
        final Preview preview;
        final String sourceDigest;
        String requestId;
        boolean running;
        Receipt receipt;
        Pending(String workspaceId, Input input, Preview preview, String sourceDigest) {
            this.workspaceId = workspaceId; this.input = input; this.preview = preview; this.sourceDigest = sourceDigest;
        }
    }
}
