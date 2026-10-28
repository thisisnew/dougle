package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.PlanDecision.OperationalTool;
import java.util.List;

public record OperationalToolProposal(String jobId, String workspaceId, String bundleId, OperationalTool kind,
                                      List<String> requiredFields, boolean executionAllowed) {
    public OperationalToolProposal {
        requiredFields = List.copyOf(requiredFields);
        if (executionAllowed) throw new IllegalArgumentException("A proposal cannot authorize execution");
    }

    public static OperationalToolProposal from(InvestigationPlanStore.Job job, OperationalTool kind) {
        return new OperationalToolProposal(job.id(), job.workspaceId(), job.bundleId(), kind,
                kind == OperationalTool.MYSQL_READ ? List.of("table", "columns", "equalities", "limit")
                        : List.of("environment", "service", "indexes", "from", "to", "limit"), false);
    }
}
