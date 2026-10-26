package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Binding;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Candidate;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Origin;
import dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.security.application.CredentialContentGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class OperationalScopeClarification {
    @FunctionalInterface
    public interface Authority {
        Binding current(String workspaceId, String threadId, String parentBundleId, OperationalTool tool);
    }

    private final Authority authority;
    private final Clock clock;
    private final Map<String, PendingOperationalScope> pending = new HashMap<>();
    private static final int MAX_PENDING = 64;

    public OperationalScopeClarification(Authority authority, Clock clock) {
        this.authority = Objects.requireNonNull(authority);
        this.clock = Objects.requireNonNull(clock);
    }

    public PendingOperationalScope open(String workspaceId, String threadId, String parentBundleId,
                                         Candidate candidate, Origin origin) {
        requireCandidate(candidate);
        var binding = current(workspaceId, threadId, parentBundleId, candidate.tool());
        var now = clock.instant();
        var scope = new PendingOperationalScope(UUID.randomUUID().toString(), binding, 1, candidate, origin,
                now, now.plus(Duration.ofMinutes(10)));
        synchronized (pending) {
            pending.values().removeIf(value -> value.expiredAt(now));
            if (pending.size() >= MAX_PENDING) throw new LibraryException(409, "조회 범위 후보가 너무 많습니다. 기존 후보를 취소하세요.");
            pending.put(scope.id(), scope);
        }
        return scope;
    }

    public PendingOperationalScope get(String workspaceId, String id) {
        var scope = owned(workspaceId, id);
        requireCandidate(scope.candidate());
        var binding = scope.binding();
        var current = revalidate(scope, binding.parentBundleId());
        if (!binding.equals(current)) throw invalidate(scope);
        synchronized (pending) {
            if (owned(workspaceId, id) != scope) throw stale();
            return scope;
        }
    }

    public PendingOperationalScope revise(String workspaceId, String id, long expectedRevision, String currentParentBundleId,
                                           Candidate replacement, Origin origin) {
        var previous = owned(workspaceId, id);
        if (previous.revision() != expectedRevision || expectedRevision == Long.MAX_VALUE) throw stale();
        requireCandidate(replacement);
        if (replacement.tool() != previous.binding().tool()) throw stale();
        var binding = revalidate(previous, currentParentBundleId);
        if (!Objects.equals(previous.binding().connectionRevision(), binding.connectionRevision())
                || !previous.binding().connectionGeneration().equals(binding.connectionGeneration())) throw invalidate(previous);
        var revised = new PendingOperationalScope(id, binding, expectedRevision + 1, replacement, origin,
                previous.createdAt(), previous.expiresAt());
        synchronized (pending) {
            if (owned(workspaceId, id) != previous) throw stale();
            pending.put(id, revised);
            return revised;
        }
    }

    public void cancel(String workspaceId, String id, long expectedRevision) {
        synchronized (pending) {
            var scope = owned(workspaceId, id);
            if (scope.revision() != expectedRevision) throw stale();
            pending.remove(id);
        }
    }

    private PendingOperationalScope owned(String workspaceId, String id) {
        synchronized (pending) {
            var scope = pending.get(id);
            if (scope == null || !scope.binding().workspaceId().equals(workspaceId)) {
                throw new LibraryException(404, "조회 범위 후보를 찾을 수 없습니다.");
            }
            if (scope.expiredAt(clock.instant())) {
                pending.remove(id);
                throw new LibraryException(409, "조회 범위 후보가 만료되었습니다. 대화에서 범위를 다시 확인하세요.");
            }
            return scope;
        }
    }

    private Binding current(String workspaceId, String threadId, String parentBundleId, OperationalTool tool) {
        new Binding(workspaceId, threadId, parentBundleId, tool, null, "validation-only");
        var binding = authority.current(workspaceId, threadId, parentBundleId, tool);
        if (binding == null || !workspaceId.equals(binding.workspaceId()) || !threadId.equals(binding.threadId())
                || !parentBundleId.equals(binding.parentBundleId()) || tool != binding.tool()) throw stale();
        return binding;
    }

    private Binding revalidate(PendingOperationalScope scope, String parentBundleId) {
        var binding = scope.binding();
        try {
            return current(binding.workspaceId(), binding.threadId(), parentBundleId, binding.tool());
        } catch (LibraryException failure) {
            if (failure.status() == 404 || failure.status() == 409) throw invalidate(scope);
            throw failure;
        }
    }

    private LibraryException invalidate(PendingOperationalScope scope) {
        synchronized (pending) {
            if (pending.get(scope.id()) == scope) pending.remove(scope.id());
        }
        return stale();
    }

    private void requireCandidate(Candidate candidate) {
        Objects.requireNonNull(candidate);
        if (candidate instanceof PendingOperationalScope.Mysql mysql) {
            if (mysql.equalities() != null) mysql.equalities().forEach((column, value) -> {
                CredentialContentGuard.requireSafe(value);
                CredentialContentGuard.requireSafe(column + "=" + value);
            });
        } else if (candidate instanceof PendingOperationalScope.Datadog datadog) {
            var now = clock.instant();
            if (datadog.from() != null && (datadog.from().isBefore(now.minus(Duration.ofDays(7))) || datadog.from().isAfter(now))
                    || datadog.to() != null && (datadog.to().isBefore(now.minus(Duration.ofDays(7))) || datadog.to().isAfter(now))) {
                throw new LibraryException(400, "로그 시각은 현재 기준 최근 7일 이내의 절대 시각으로 지정하세요.");
            }
        }
    }

    private static LibraryException stale() { return new LibraryException(409, "조회 범위 후보 또는 대화·연결이 변경되었습니다. 다시 확인하세요."); }
}
