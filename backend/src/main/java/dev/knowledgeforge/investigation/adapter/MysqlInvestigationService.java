package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import dev.knowledgeforge.plugins.adapter.PluginConnections;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class MysqlInvestigationService implements AutoCloseable {
    public record Preview(String id, String workspaceId, String connection, String database,
                          MysqlReadClient.Request query, String expiresAt, int maxBytes, int timeoutSeconds) {}
    public record Result(String id, String status, String output, int rows, boolean truncated, String capturedAt) {}
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Runnable NO_SCOPE_CHECK = () -> {};
    private final PluginConnections connections;
    private final MysqlReadClient reader;
    private final LibraryStore library;
    private final Clock clock;
    private final Duration validationTimeout;
    private final Duration executionTimeout;
    private final ThreadPoolExecutor validationWorkers = new ThreadPoolExecutor(2, 2, 2, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), Thread.ofPlatform().daemon().name("mysql-validation-", 0).factory());
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Pending> pending = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public MysqlInvestigationService(PluginConnections connections, MysqlClient client, LibraryStore library) {
        this(connections, new MysqlReadClient(client), library);
    }

    MysqlInvestigationService(PluginConnections connections, MysqlReadClient reader, LibraryStore library) {
        this(connections, reader, library, Clock.systemUTC());
    }

    MysqlInvestigationService(PluginConnections connections, MysqlReadClient reader, LibraryStore library, Clock clock) {
        this(connections, reader, library, clock, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    MysqlInvestigationService(PluginConnections connections, MysqlReadClient reader, LibraryStore library, Clock clock,
                              Duration validationTimeout, Duration executionTimeout) {
        this.connections = connections;
        this.reader = reader;
        this.library = library;
        this.clock = clock;
        this.validationTimeout = validationTimeout;
        this.executionTimeout = executionTimeout;
        validationWorkers.allowCoreThreadTimeOut(true);
    }

    public Preview preview(String workspaceId, MysqlReadClient.Request query) {
        long deadline = deadline();
        var connection = validate(workspaceId, deadline, null);
        var config = connection.config();
        if (query == null || query.equalities().isEmpty() || query.limit() > 50) {
            throw new LibraryException(400, "조회 조건을 하나 이상 지정하고 최대 50행으로 제한하세요.");
        }
        var columns = new HashSet<>(query.columns());
        columns.addAll(query.equalities().keySet());
        if (columns.stream().anyMatch(column -> column.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(password|passwd|secret|token|credential|private_?key|api_?key|authorization|cookie).*"))) {
            throw new LibraryException(400, "인증 정보 열은 조회할 수 없습니다.");
        }
        var access = new MysqlReadClient.Access(config.database(), Map.of(query.table(), columns), 50, 16384, Duration.ofSeconds(10));
        reader.validate(config, access, query);
        Instant now = clock.instant();
        var preview = new Preview(UUID.randomUUID().toString(), workspaceId, config.host() + ":" + config.port(),
                config.database(), query, now.plus(TTL).toString(), access.maxBytes(), 10);
        synchronized (pending) {
            pending.values().removeIf(value -> !value.running && !now.isBefore(Instant.parse(value.preview.expiresAt())));
            if (pending.size() >= 32) throw new LibraryException(429, "조회 미리보기가 많습니다. 잠시 후 다시 시도하세요.");
            var provenance = OperationalResultText.provenance(Map.of("database", config.database(), "table", query.table(),
                    "columns", query.columns(), "equalities", new java.util.TreeMap<>(query.equalities()), "limit", query.limit(),
                    "environment", "미기록 (호스트로 추정하지 말 것)", "timeWindow", "별도 승인 시간 범위 없음; equality 조건만 적용",
                    "timezone", "DB 세션 시간대 미기록; 시간 문자열의 시간대를 추정하지 말 것"), java.util.List.of(config.password()));
            pending.put(preview.id(), new Pending(preview, connection.binding(), connection.epoch(), access, provenance));
        }
        return preview;
    }

    public Result execute(String workspaceId, String previewId, String requestId, boolean approved) {
        return execute(workspaceId, previewId, requestId, approved, NO_SCOPE_CHECK);
    }

    public Result execute(String workspaceId, String previewId, String requestId, boolean approved, Runnable scopeCheckpoint) {
        if (!approved) throw new LibraryException(403, "표시한 DB와 조회 범위 실행에 동의하세요.");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new LibraryException(400, "요청 식별자가 필요합니다.");
        java.util.Objects.requireNonNull(scopeCheckpoint);
        long deadline = deadline();
        checkpoint(null, deadline, scopeCheckpoint);
        var connection = validate(workspaceId, deadline, null);
        checkpoint(null, deadline, scopeCheckpoint);
        var config = connection.config();
        Pending operation;
        synchronized (pending) {
            operation = owned(workspaceId, previewId);
            if (!operation.binding.equals(connection.binding()) || operation.epoch != connection.epoch()) throw new LibraryException(409, "DB 연결이 변경되었습니다. 새 미리보기가 필요합니다.");
            if (operation.cancelled && operation.requestId == null) throw new LibraryException(409, "취소한 조회입니다. 새 미리보기가 필요합니다.");
            if (operation.requestId != null) {
                if (!operation.requestId.equals(requestId)) throw new LibraryException(409, "이미 사용한 조회 동의입니다.");
                if (operation.result != null) return operation.result;
                throw new LibraryException(409, "조회가 진행 중입니다. 같은 요청의 결과를 확인하세요.");
            }
            if (!clock.instant().isBefore(Instant.parse(operation.preview.expiresAt()))) {
                throw new LibraryException(409, "DB 연결 또는 동의가 변경되었습니다. 새 미리보기를 확인하세요.");
            }
            if (pending.values().stream().anyMatch(value -> value.running)) throw new LibraryException(409, "다른 DB 조회가 진행 중입니다.");
            operation.requestId = requestId;
            operation.running = true;
        }
        Result result;
        try {
            checkpoint(operation, deadline, scopeCheckpoint);
            var access = operation.access;
            Duration remaining = Duration.ofNanos(Math.max(1_000_000, deadline - System.nanoTime()));
            if (remaining.compareTo(access.timeout()) > 0) remaining = access.timeout();
            var boundedAccess = new MysqlReadClient.Access(access.schema(), access.tables(), access.maxRows(), access.maxBytes(), remaining);
            var observed = reader.read(config, boundedAccess, operation.preview.query(), () -> checkpoint(operation, deadline, scopeCheckpoint));
            var current = validate(workspaceId, deadline, operation);
            if (!operation.binding.equals(current.binding()) || operation.epoch != current.epoch()) throw new CancellationException();
            checkpoint(operation, deadline, scopeCheckpoint);
            var bounded = OperationalResultText.redactAndBound(observed.output(), config.password(), operation.access.maxBytes(), observed.truncated());
            String output = bounded.text();
            checkpoint(operation, deadline, scopeCheckpoint);
            result = new Result(previewId, output.isEmpty() && !bounded.truncated() ? "EMPTY" : "SUCCEEDED", output, observed.rows(), bounded.truncated(), Instant.now().toString());
        } catch (CancellationException cancelled) {
            result = new Result(previewId, "CANCELLED", "", 0, false, Instant.now().toString());
        } catch (java.sql.SQLTimeoutException timeout) {
            result = new Result(previewId, "TIMEOUT", "", 0, false, Instant.now().toString());
        } catch (LibraryException failure) {
            result = new Result(previewId, failure.status() == 504 ? "TIMEOUT" : "FAILED", "", 0, false, Instant.now().toString());
        } catch (Exception failure) {
            result = new Result(previewId, "FAILED", "", 0, false, Instant.now().toString());
        }
        synchronized (pending) {
            if (operation.cancelled) result = new Result(previewId, "CANCELLED", "", 0, false, Instant.now().toString());
            operation.result = result;
            operation.running = false;
        }
        return result;
    }

    public Result result(String workspaceId, String previewId) {
        var connection = validate(workspaceId, deadline(), null);
        synchronized (pending) {
            var operation = owned(workspaceId, previewId);
            if (!operation.binding.equals(connection.binding()) || operation.epoch != connection.epoch()
                    || connection.epoch() != connections.mysqlEpoch()) throw new LibraryException(409, "DB 연결이 변경되었습니다.");
            if (operation.result != null) return operation.result;
            return new Result(previewId, operation.running ? "RUNNING" : "PREVIEW", "", 0, false, null);
        }
    }

    public void cancel(String workspaceId, String previewId) {
        synchronized (pending) {
            var operation = owned(workspaceId, previewId);
            if (operation.result == null) {
                operation.cancelled = true;
                if (!operation.running) operation.result = new Result(previewId, "CANCELLED", "", 0, false, Instant.now().toString());
            }
        }
    }

    public OperationalResultText.Text provenance(String workspaceId, String previewId) {
        result(workspaceId, previewId);
        synchronized (pending) { return owned(workspaceId, previewId).provenance; }
    }

    private Pending owned(String workspaceId, String id) {
        var value = pending.get(id);
        if (value == null || !value.preview.workspaceId().equals(workspaceId)) throw new LibraryException(404, "조회가 없거나 앱을 다시 시작했습니다. 새 미리보기가 필요합니다.");
        if (!value.running && !clock.instant().isBefore(Instant.parse(value.preview.expiresAt()))) {
            pending.remove(id);
            throw new LibraryException(409, "조회가 만료되었습니다. 새 미리보기가 필요합니다.");
        }
        return value;
    }

    private MysqlClient.Config configuration() {
        var config = connections.mysqlConfig();
        if (config == null) throw new LibraryException(409, "설정에서 MySQL을 연결하세요.");
        return config;
    }

    private String binding(MysqlClient.Config config, String revision) {
        return ContentDigest.of(json.createObjectNode().put("connection", json.writeValueAsString(config))
                .put("revision", revision).toString());
    }
    private void requireWorkspace(String workspaceId) {
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
    }

    private record Validated(MysqlClient.Config config, String binding, long epoch) {}

    private Validated validate(String workspaceId, long deadline, Pending operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            checkWaiting(operation, deadline);
            requireWorkspace(workspaceId);
            checkWaiting(operation, deadline);
        } else boundedValidation(() -> { requireWorkspace(workspaceId); return true; }, deadline, operation);
        return boundedValidation(() -> {
            long epoch = connections.mysqlEpoch();
            var config = configuration();
            String revision = connections.mysqlRevision();
            if (epoch != connections.mysqlEpoch()) throw new LibraryException(409, "DB 연결이 변경되었습니다. 다시 확인하세요.");
            return new Validated(config, binding(config, revision), epoch);
        }, deadline, operation);
    }

    private <Value> Value boundedValidation(Callable<Value> validation, long deadline, Pending operation) {
        checkWaiting(operation, deadline);
        long validationDeadline = System.nanoTime() + Math.min(validationTimeout.toNanos(), deadline - System.nanoTime());
        java.util.concurrent.Future<Value> task;
        try { task = validationWorkers.submit(validation); }
        catch (RejectedExecutionException busy) { throw new LibraryException(503, "DB 연결 검증이 사용 중입니다. 잠시 후 다시 확인하세요."); }
        try {
            while (true) {
                checkWaiting(operation, deadline);
                long remaining = validationDeadline - System.nanoTime();
                if (remaining <= 0) throw new LibraryException(504, "DB 연결 검증 시간이 초과되었습니다.");
                try {
                    Value result = task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS);
                    checkWaiting(operation, deadline);
                    return result;
                } catch (TimeoutException waiting) {
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof LibraryException problem) throw problem;
            if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new LibraryException(500, "DB 연결 정보를 확인하지 못했습니다.");
        } finally {
            task.cancel(true);
            if (task instanceof Runnable queued) validationWorkers.remove(queued);
        }
    }

    private long deadline() { return System.nanoTime() + executionTimeout.toNanos(); }
    private void checkWaiting(Pending operation, long deadline) {
        if (Thread.currentThread().isInterrupted() || operation != null && operation.cancelled) throw new CancellationException();
        if (operation != null && connections.mysqlEpoch() != operation.epoch) throw new CancellationException();
        if (System.nanoTime() >= deadline) throw new LibraryException(504, "DB 조회 시간이 초과되었습니다.");
    }
    private void checkpoint(Pending operation, long deadline) {
        checkWaiting(operation, deadline);
    }

    private void checkpoint(Pending operation, long deadline, Runnable scopeCheckpoint) {
        checkpoint(operation, deadline);
        if (scopeCheckpoint != NO_SCOPE_CHECK) {
            boundedValidation(() -> { scopeCheckpoint.run(); return true; }, deadline, operation);
        }
        checkpoint(operation, deadline);
    }

    @Override @jakarta.annotation.PreDestroy
    public void close() { validationWorkers.shutdownNow(); }

    private static final class Pending {
        final Preview preview;
        final String binding;
        final long epoch;
        final MysqlReadClient.Access access;
        final OperationalResultText.Text provenance;
        String requestId;
        boolean running;
        volatile boolean cancelled;
        Result result;
        Pending(Preview preview, String binding, long epoch, MysqlReadClient.Access access, OperationalResultText.Text provenance) {
            this.preview = preview; this.binding = binding; this.epoch = epoch; this.access = access;
            this.provenance = provenance;
        }
    }
}
