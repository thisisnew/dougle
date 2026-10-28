package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.ConstrainedToolExecutor;
import dev.dougle.investigation.domain.ToolExecution;

public interface LocalInvestigationTools {
    String SEARCH_ID = "local.workspace.search";
    static ToolExecution.Binding binding(String workspaceId) {
        return new ToolExecution.Binding(workspaceId, "local", "workspace-search");
    }
    ConstrainedToolExecutor open(String workspaceId, ToolExecution.Budget budget, ToolExecution.Cancellation cancellation);
    ToolExecution.Result search(String workspaceId, String jobId, String query, ToolExecution.Budget budget,
                                ToolExecution.Cancellation cancellation);
    dev.dougle.search.application.LocalSearchSnapshot snapshot(String workspaceId, String jobId, String query);
}
