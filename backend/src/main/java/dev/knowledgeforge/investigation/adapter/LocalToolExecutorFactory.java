package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.domain.ConstrainedToolExecutor;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.application.SearchService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalToolExecutorFactory implements dev.knowledgeforge.investigation.application.LocalInvestigationTools {
    private final SearchService search;
    private final LibraryStore library;

    public LocalToolExecutorFactory(SearchService search, LibraryStore library) {
        this.search = search;
        this.library = library;
    }

    public ConstrainedToolExecutor open(String workspaceId, ToolExecution.Budget budget,
                                         ToolExecution.Cancellation cancellation) {
        var binding = LocalWorkspaceSearchTool.binding(workspaceId);
        if (library.workspace(workspaceId).isEmpty()) throw new IllegalArgumentException("Workspace does not exist");
        return new ConstrainedToolExecutor(List.of(new LocalWorkspaceSearchTool(search, library, workspaceId)),
                binding, budget, cancellation);
    }
}
