package dev.dougle.investigation.adapter;

import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.investigation.application.OperationalScopeClarification;
import dev.dougle.investigation.domain.PendingOperationalScope.Binding;
import dev.dougle.investigation.domain.PlanDecision.OperationalTool;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.plugins.adapter.PluginConnections;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ServerOperationalScopeAuthority implements OperationalScopeClarification.Authority {
    private final LibraryStore library;
    private final ChatThreadService threads;
    private final PluginConnections mysql;

    public ServerOperationalScopeAuthority(LibraryStore library, ChatThreadService threads,
                                           PluginConnections mysql) {
        this.library = library;
        this.threads = threads;
        this.mysql = mysql;
    }

    @Override
    @Transactional(readOnly = true)
    public Binding current(String workspaceId, String threadId, String parentBundleId, OperationalTool tool) {
        new Binding(workspaceId, threadId, parentBundleId, tool, null, "validation-only");
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        var thread = threads.find(workspaceId, threadId)
                .orElseThrow(() -> new LibraryException(404, "대화를 찾을 수 없습니다."));
        if (!thread.bundles().getLast().id().equals(parentBundleId)) {
            throw new LibraryException(409, "대화가 변경되었습니다. 다시 확인하세요.");
        }
        return switch (tool) {
            case MYSQL_READ -> {
                var metadata = mysql.mysqlScopeMetadata();
                yield new Binding(workspaceId, threadId, parentBundleId, tool, metadata.revision(), metadata.generation());
            }
            case DATADOG_LOGS -> throw new LibraryException(410, "제거된 연결 기능입니다.");
        };
    }
}
