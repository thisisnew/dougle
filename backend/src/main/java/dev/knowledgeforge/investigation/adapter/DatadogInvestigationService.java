package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.DatadogConnections;
import dev.knowledgeforge.plugins.adapter.DatadogLogsClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;

@Service
public class DatadogInvestigationService {
    public record Query(String connectionRevision, String environment, String service, Set<String> indexes,
                        Instant from, Instant to, int limit) {
        public Query { indexes = Set.copyOf(indexes); }
    }
    public record Preview(String id, String workspaceId, String site, Query query, String expiresAt,
                          int maxBytes, int timeoutSeconds, boolean sessionOnly) {}
    public record Result(String id, String status, String output, int rows, boolean truncated, String capturedAt) {}
    private final DatadogConnections connections;
    private final DatadogLogsClient client;
    private final Clock clock;
    private final Map<String, Pending> pending = new HashMap<>();

    public DatadogInvestigationService(DatadogConnections connections, DatadogLogsClient client, Clock clock) {
        this.connections = connections; this.client = client; this.clock = clock;
    }

    public Preview preview(String workspaceId, Query input) {
        if (input == null) throw new LibraryException(400, "조회 범위를 지정하세요.");
        var config = connections.require(workspaceId, input.connectionRevision());
        DatadogLogsClient.Query query;
        DatadogLogsClient.Policy policy;
        Instant now = clock.instant();
        try {
            query = new DatadogLogsClient.Query(input.environment(), input.service(), input.from(), input.to(), input.limit());
            if (input.limit() > 50 || input.from().isBefore(now.minus(Duration.ofDays(7))) || input.to().isAfter(now)) {
                throw new IllegalArgumentException();
            }
            policy = new DatadogLogsClient.Policy(Map.of(input.environment(), Set.of(input.service())), input.indexes(),
                    input.from(), input.to(), Duration.ofHours(1), 50, 16384, Duration.ofSeconds(10));
            DatadogLogsClient.validate(policy, query);
        } catch (RuntimeException invalid) {
            throw new LibraryException(400, "환경·서비스·인덱스를 지정하고 최근 7일 내 최대 1시간, 50개 로그로 제한하세요.");
        }
        var preview = new Preview(UUID.randomUUID().toString(), workspaceId, config.site().name(), input,
                now.plus(Duration.ofMinutes(10)).toString(), 16384, 10, true);
        synchronized (pending) {
            pending.values().removeIf(value -> !value.running && !now.isBefore(Instant.parse(value.preview.expiresAt())));
            if (pending.size() >= 32) throw new LibraryException(429, "조회 미리보기가 많습니다. 잠시 후 다시 시도하세요.");
            var provenance = OperationalResultText.provenance(Map.of("environment", input.environment(), "service", input.service(),
                    "indexes", input.indexes().stream().sorted().toList(), "from", input.from().toString(), "to", input.to().toString(),
                    "timezone", "UTC", "limit", input.limit(), "site", config.site().name()),
                    java.util.List.of(config.apiKey(), config.applicationKey()));
            pending.put(preview.id(), new Pending(preview, query, policy, provenance));
        }
        return preview;
    }

    public Result execute(String workspaceId, String id, String requestId, boolean approved) {
        if (!approved) throw new LibraryException(403, "표시한 Datadog 조회 범위 실행에 동의하세요.");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw new LibraryException(400, "요청 식별자가 필요합니다.");
        Pending operation = owned(workspaceId, id);
        var config = connections.require(workspaceId, operation.preview.query().connectionRevision());
        synchronized (pending) {
            ensureAvailable(operation);
            if (operation.requestId != null) {
                if (!operation.requestId.equals(requestId)) throw new LibraryException(409, "이미 사용한 조회 동의입니다.");
                return operation.result == null ? state(operation, "RUNNING") : operation.result;
            }
            if (operation.cancelled) throw new LibraryException(409, "취소한 조회입니다. 새 미리보기가 필요합니다.");
            if (pending.values().stream().anyMatch(value -> value.running)) throw new LibraryException(409, "다른 Datadog 조회가 진행 중입니다.");
            operation.requestId = requestId;
            operation.running = true;
        }
        Result result;
        try {
            var observed = client.read(config, operation.policy, operation.query, () -> {
                if (operation.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
            });
            connections.require(workspaceId, operation.preview.query().connectionRevision());
            if (operation.cancelled) throw new CancellationException();
            result = new Result(id, observed.output().isEmpty() ? "EMPTY" : "OK", observed.output(), observed.rows(), observed.truncated(), clock.instant().toString());
        } catch (CancellationException cancelled) {
            result = state(operation, "CANCELLED");
        } catch (LibraryException failure) {
            result = state(operation, failure.status() == 504 ? "TIMEOUT"
                    : Set.of(401, 403, 409).contains(failure.status()) ? "DENIED" : "FAILED");
        } catch (RuntimeException failure) {
            result = state(operation, "FAILED");
        }
        synchronized (pending) {
            operation.result = operation.cancelled ? state(operation, "CANCELLED") : result;
            operation.running = false;
            return operation.result;
        }
    }

    public Result result(String workspaceId, String id) {
        var operation = owned(workspaceId, id);
        connections.require(workspaceId, operation.preview.query().connectionRevision());
        synchronized (pending) {
            ensureAvailable(operation);
            return operation.result == null ? state(operation, operation.running ? "RUNNING" : "PREVIEW") : operation.result;
        }
    }

    public void cancel(String workspaceId, String id) {
        var operation = owned(workspaceId, id);
        synchronized (pending) {
            if (operation.result == null) {
                operation.cancelled = true;
                if (!operation.running) operation.result = state(operation, "CANCELLED");
            }
        }
    }

    public OperationalResultText.Text provenance(String workspaceId, String id) {
        result(workspaceId, id);
        return owned(workspaceId, id).provenance;
    }

    private Pending owned(String workspaceId, String id) {
        connections.status(workspaceId);
        synchronized (pending) {
            var value = pending.get(id);
            if (value == null || !value.preview.workspaceId().equals(workspaceId)) {
                throw new LibraryException(404, "조회가 없거나 앱을 다시 시작했습니다. 새 미리보기가 필요합니다.");
            }
            ensureAvailable(value);
            return value;
        }
    }

    private void ensureAvailable(Pending value) {
        if (!value.running && !clock.instant().isBefore(Instant.parse(value.preview.expiresAt()))) {
            pending.remove(value.preview.id());
            throw new LibraryException(409, "조회 미리보기가 만료되었습니다. 새 범위를 확인하세요.");
        }
    }

    private Result state(Pending value, String status) { return new Result(value.preview.id(), status, "", 0, false, null); }

    private static final class Pending {
        final Preview preview;
        final DatadogLogsClient.Query query;
        final DatadogLogsClient.Policy policy;
        final OperationalResultText.Text provenance;
        String requestId;
        boolean running;
        volatile boolean cancelled;
        Result result;
        Pending(Preview preview, DatadogLogsClient.Query query, DatadogLogsClient.Policy policy, OperationalResultText.Text provenance) {
            this.preview = preview; this.query = query; this.policy = policy;
            this.provenance = provenance;
        }
    }
}
