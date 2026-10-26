package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.domain.ConstrainedToolExecutor;
import dev.knowledgeforge.investigation.domain.ReadOnlyTool;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.plugins.adapter.DatadogConnections;
import dev.knowledgeforge.plugins.adapter.DatadogLogsClient;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class DatadogReadTool implements ReadOnlyTool<DatadogLogsClient.Query> {
    public static final String ID = "datadog.logs.read";
    public record Approval(ToolExecution.Binding binding, String connectionRevision, DatadogLogsClient.Policy policy) {
        public Approval {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(policy);
            if (!"datadog-logs-read".equals(binding.scope()) || "*".equals(binding.workspace())
                    || !policy.servicesByEnvironment().containsKey(binding.environment())
                    || connectionRevision == null || !connectionRevision.matches("[a-f0-9-]{36}")) {
                throw new IllegalArgumentException("Explicit Datadog approval required");
            }
        }
    }

    private final DatadogLogsClient client;
    private final DatadogConnections connections;
    private final Approval approval;

    public DatadogReadTool(DatadogLogsClient client, DatadogConnections connections, Approval approval) {
        this.client = Objects.requireNonNull(client);
        this.connections = Objects.requireNonNull(connections);
        this.approval = Objects.requireNonNull(approval);
    }

    public static ConstrainedToolExecutor openApproved(DatadogLogsClient client, DatadogConnections connections, Approval approval,
                                                        ToolExecution.Budget budget, ToolExecution.Cancellation cancellation) {
        connections.require(approval.binding().workspace(), approval.connectionRevision());
        return new ConstrainedToolExecutor(List.of(new DatadogReadTool(client, connections, approval)), approval.binding(), budget, cancellation);
    }

    @Override public String id() { return ID; }
    @Override public Class<DatadogLogsClient.Query> inputType() { return DatadogLogsClient.Query.class; }
    @Override public Set<ToolExecution.Binding> bindings() { return Set.of(approval.binding()); }
    @Override public String invocationKey(DatadogLogsClient.Query input) { return input.toString(); }

    @Override
    public void execute(DatadogLogsClient.Query input, ToolExecution.Context context, ToolExecution.Output output) {
        context.checkpoint();
        if (!approval.binding().equals(context.binding()) || !input.environment().equals(approval.binding().environment())) {
            throw new IllegalArgumentException("Datadog binding mismatch");
        }
        var config = connections.require(approval.binding().workspace(), approval.connectionRevision());
        var result = client.read(config, approval.policy(), input, context::checkpoint);
        context.checkpoint();
        connections.require(approval.binding().workspace(), approval.connectionRevision());
        output.append(result.output());
        context.checkpoint();
    }
}
