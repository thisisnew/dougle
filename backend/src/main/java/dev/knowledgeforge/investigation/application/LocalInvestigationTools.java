package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.ConstrainedToolExecutor;
import dev.knowledgeforge.investigation.domain.ToolExecution;

public interface LocalInvestigationTools {
    String SEARCH_ID = "local.workspace.search";
    static ToolExecution.Binding binding(String workspaceId) {
        return new ToolExecution.Binding(workspaceId, "local", "workspace-search");
    }
    ConstrainedToolExecutor open(String workspaceId, ToolExecution.Budget budget, ToolExecution.Cancellation cancellation);
}
