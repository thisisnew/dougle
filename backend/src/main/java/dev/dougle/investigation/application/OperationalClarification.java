package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.PendingOperationalScope;
import dev.dougle.investigation.domain.PlanDecision.OperationalTool;
import java.util.List;

public record OperationalClarification(String jobId, String scopeId, long revision, String workspaceId,
                                       String threadId, String parentBundleId, OperationalTool tool,
                                       String expiresAt, boolean sessionOnly, boolean executionAllowed,
                                       boolean internalResolutionRequired, List<Question> questions) {
    public record Question(PendingOperationalScope.UserContext topic, String prompt) {}

    public OperationalClarification {
        if (executionAllowed || !sessionOnly) throw new IllegalArgumentException("Non-authorizing session scope required");
        questions = List.copyOf(questions);
    }

    public static OperationalClarification from(String jobId, PendingOperationalScope scope) {
        var questions = scope.missingUserContext().stream().map(topic -> new Question(topic, switch (topic) {
            case ENVIRONMENT -> "어느 환경에서 발생한 현상인지 알려 주세요.";
            case TIME_WINDOW -> "현상이 발생한 시간 범위를 알려 주세요.";
            case TIME_ZONE -> "말씀하신 시간은 어느 시간대 기준인가요?";
        })).toList();
        var binding = scope.binding();
        return new OperationalClarification(jobId, scope.id(), scope.revision(), binding.workspaceId(),
                binding.threadId(), binding.parentBundleId(), binding.tool(), scope.expiresAt().toString(),
                true, false, !scope.missingInternalFields().isEmpty(), questions);
    }
}
