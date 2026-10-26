package dev.knowledgeforge.investigation.adapter;

import static dev.knowledgeforge.investigation.domain.ToolExecution.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.DatadogConnections;
import dev.knowledgeforge.plugins.adapter.DatadogLogsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatadogReadToolTest {
    private final DatadogConnections connections = mock(DatadogConnections.class);
    private final DatadogLogsClient client = mock(DatadogLogsClient.class);
    private final String revision = "5d5cc45e-f6ef-4689-8450-a021e35e9cf9";
    private final ToolExecution.Binding binding = new ToolExecution.Binding("workspace", "test", "datadog-logs-read");
    private final Instant from = Instant.parse("2025-01-01T00:00:00Z");
    private final DatadogLogsClient.Query query = new DatadogLogsClient.Query("test", "orders", from, from.plusSeconds(60), 1);
    private final DatadogLogsClient.Policy policy = new DatadogLogsClient.Policy(Map.of("test", Set.of("orders")), Set.of("index"),
            from, from.plusSeconds(600), Duration.ofMinutes(10), 10, 1024, Duration.ofSeconds(2));
    private final DatadogReadTool.Approval approval = new DatadogReadTool.Approval(binding, revision, policy);
    private final DatadogLogsClient.Config config = new DatadogLogsClient.Config(DatadogLogsClient.Site.US1,
            "synthetic_api_key_0000", "synthetic_app_key_0000");
    private final ToolExecution.Budget budget = new ToolExecution.Budget(5, Duration.ofSeconds(5), Duration.ofSeconds(2), 2048, 1024);

    @BeforeEach
    void prepare() {
        when(connections.require("workspace", revision)).thenReturn(config);
        when(client.read(eq(config), eq(policy), eq(query), any())).thenReturn(new DatadogLogsClient.Result("redacted log", 1, false));
    }

    @Test
    void approvedToolExecutesBoundQueryAndRejectsIdenticalReplay() {
        try (var executor = DatadogReadTool.openApproved(client, connections, approval, budget, new ToolExecution.Cancellation())) {
            assertEquals("redacted log", executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, query)).output());
            assertEquals("duplicate_invocation", executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, query)).reason());
        }
        verify(client).read(eq(config), eq(policy), eq(query), any());
        verify(connections, times(3)).require("workspace", revision);
    }

    @Test
    void wrongWorkspaceBindingAndBodyEnvironmentCannotReachDatadog() {
        try (var executor = DatadogReadTool.openApproved(client, connections, approval, budget, new ToolExecution.Cancellation())) {
            var other = new ToolExecution.Binding("other", "test", "datadog-logs-read");
            assertEquals(DENIED, executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, other, query)).status());
            var production = new DatadogLogsClient.Query("prod", "orders", from, from.plusSeconds(60), 1);
            assertEquals(FAILED, executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, production)).status());
        }
        verifyNoInteractions(client);
    }

    @Test
    void changingConnectionDuringReadDiscardsReturnedLogs() {
        when(client.read(eq(config), eq(policy), eq(query), any())).thenAnswer(invocation -> {
            when(connections.require("workspace", revision)).thenThrow(new LibraryException(409, "Changed"));
            return new DatadogLogsClient.Result("private old connection log", 1, false);
        });
        try (var executor = DatadogReadTool.openApproved(client, connections, approval, budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, query));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void cancellationBeforeAndAfterReadNeverReturnsLogs() {
        var cancellation = new ToolExecution.Cancellation();
        cancellation.cancel();
        try (var executor = DatadogReadTool.openApproved(client, connections, approval, budget, cancellation)) {
            assertEquals(CANCELLED, executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, query)).status());
        }
        verifyNoInteractions(client);
        var during = new ToolExecution.Cancellation();
        when(client.read(eq(config), eq(policy), eq(query), any())).thenAnswer(invocation -> {
            during.cancel();
            return new DatadogLogsClient.Result("private", 1, false);
        });
        try (var executor = DatadogReadTool.openApproved(client, connections, approval, budget, during)) {
            var result = executor.execute(new ToolExecution.Invocation<>(DatadogReadTool.ID, binding, query));
            assertEquals(CANCELLED, result.status());
            assertEquals("", result.output());
        }
    }
}
