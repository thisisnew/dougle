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
    private final dev.knowledgeforge.knowledge.application.KnowledgeStore knowledge;
    private final java.time.Clock clock;
    private record Key(String workspaceId, String jobId) {}
    private final java.util.Map<Key, dev.knowledgeforge.search.application.LocalSearchSnapshot> snapshots = new java.util.HashMap<>();
    private final java.util.Set<Key> running = new java.util.HashSet<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LocalToolExecutorFactory(SearchService search, LibraryStore library,
                                    dev.knowledgeforge.knowledge.application.KnowledgeStore knowledge) {
        this(search, library, knowledge, java.time.Clock.systemUTC());
    }

    LocalToolExecutorFactory(SearchService search, LibraryStore library,
                             dev.knowledgeforge.knowledge.application.KnowledgeStore knowledge, java.time.Clock clock) {
        this.search = search;
        this.library = library;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    public ConstrainedToolExecutor open(String workspaceId, ToolExecution.Budget budget,
                                         ToolExecution.Cancellation cancellation) {
        var binding = LocalWorkspaceSearchTool.binding(workspaceId);
        if (library.workspace(workspaceId).isEmpty()) throw new IllegalArgumentException("Workspace does not exist");
        return new ConstrainedToolExecutor(List.of(new LocalWorkspaceSearchTool(search, library, workspaceId)),
                binding, budget, cancellation);
    }

    @Override
    public ToolExecution.Result search(String workspaceId, String jobId, String query, ToolExecution.Budget budget,
                                       ToolExecution.Cancellation cancellation) {
        var input = new dev.knowledgeforge.investigation.application.LocalSearchQuery(query);
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{16,64}")) throw new IllegalArgumentException("Invalid search job");
        var key = new Key(workspaceId, jobId);
        synchronized (this) {
            prune();
            if (snapshots.size() + running.size() >= 32 || snapshots.containsKey(key) || !running.add(key)) {
                throw new dev.knowledgeforge.library.application.LibraryException(409, "검색 스냅샷이 이미 있거나 저장 한도에 도달했습니다.");
            }
        }
        var inspected = new java.util.concurrent.atomic.AtomicReference<List<dev.knowledgeforge.search.application.LocalSearchSnapshot.Entry>>();
        try (var executor = new ConstrainedToolExecutor(List.of(new LocalWorkspaceSearchTool(search, library, workspaceId,
                knowledge, inspected::set)), LocalWorkspaceSearchTool.binding(workspaceId), budget, cancellation)) {
            var result = executor.execute(new ToolExecution.Invocation<>(LocalWorkspaceSearchTool.ID,
                    LocalWorkspaceSearchTool.binding(workspaceId), input));
            synchronized (this) {
                if (result.status() == ToolExecution.Status.OK && !result.truncated() && !cancellation.isCancelled()
                        && !Thread.currentThread().isInterrupted() && inspected.get() != null && !inspected.get().isEmpty()) {
                    snapshots.put(key, new dev.knowledgeforge.search.application.LocalSearchSnapshot(workspaceId, jobId,
                            input.question(), clock.instant().plusSeconds(600), inspected.get()));
                }
            }
            return result;
        } finally {
            synchronized (this) { running.remove(key); }
        }
    }

    @Override
    public dev.knowledgeforge.search.application.LocalSearchSnapshot snapshot(String workspaceId, String jobId, String query) {
        if (library.workspace(workspaceId).isEmpty()) {
            throw new dev.knowledgeforge.library.application.LibraryException(409, "검색 스냅샷이 없거나 만료되었습니다. 새 검색이 필요합니다.");
        }
        synchronized (this) {
            prune();
            var snapshot = snapshots.get(new Key(workspaceId, jobId));
            if (snapshot == null || !snapshot.query().equals(query)) {
                throw new dev.knowledgeforge.library.application.LibraryException(409, "검색 스냅샷이 없거나 만료되었습니다. 새 검색이 필요합니다.");
            }
            return snapshot;
        }
    }

    private void prune() {
        snapshots.values().removeIf(value -> !clock.instant().isBefore(value.expiresAt()));
    }
}
