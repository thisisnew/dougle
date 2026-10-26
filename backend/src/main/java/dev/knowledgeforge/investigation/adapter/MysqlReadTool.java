package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.domain.ConstrainedToolExecutor;
import dev.knowledgeforge.investigation.domain.ReadOnlyTool;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MysqlReadTool implements ReadOnlyTool<MysqlReadClient.Request> {
    public static final String ID = "mysql.read";

    public record Approval(ToolExecution.Binding binding, MysqlClient.Config config, MysqlReadClient.Access access) {
        public Approval {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(config);
            Objects.requireNonNull(access);
            if (!"mysql-read".equals(binding.scope()) || "*".equals(binding.environment())
                    || "*".equals(binding.workspace()) || !access.schema().equals(config.database())) {
                throw new IllegalArgumentException("Explicit MySQL binding required");
            }
        }

        @Override public String toString() { return "MySQL read approval [redacted]"; }
    }

    private final MysqlReadClient client;
    private final LibraryStore library;
    private final Approval approval;

    public MysqlReadTool(MysqlReadClient client, LibraryStore library, Approval approval) {
        this.client = Objects.requireNonNull(client);
        this.library = Objects.requireNonNull(library);
        this.approval = Objects.requireNonNull(approval);
    }

    public static ConstrainedToolExecutor openApproved(MysqlReadClient client, LibraryStore library, Approval approval,
                                                        ToolExecution.Budget budget, ToolExecution.Cancellation cancellation) {
        var tool = new MysqlReadTool(client, library, approval);
        tool.requireWorkspace();
        return new ConstrainedToolExecutor(List.of(tool), approval.binding(), budget, cancellation);
    }

    @Override public String id() { return ID; }
    @Override public Class<MysqlReadClient.Request> inputType() { return MysqlReadClient.Request.class; }
    @Override public Set<ToolExecution.Binding> bindings() { return Set.of(approval.binding()); }
    @Override public String invocationKey(MysqlReadClient.Request input) { return input.key(); }

    @Override
    public void execute(MysqlReadClient.Request input, ToolExecution.Context context, ToolExecution.Output output) throws Exception {
        context.checkpoint();
        if (!approval.binding().equals(context.binding())) throw new IllegalArgumentException("MySQL binding mismatch");
        requireWorkspace();
        var result = client.read(approval.config(), approval.access(), input, context::checkpoint);
        context.checkpoint();
        requireWorkspace();
        output.append(result.output());
        context.checkpoint();
    }

    private void requireWorkspace() {
        if (library.workspace(approval.binding().workspace()).isEmpty()) throw new IllegalStateException("Workspace unavailable");
    }
}
