package dev.knowledgeforge.investigation.adapter;

import static dev.knowledgeforge.investigation.domain.ToolExecution.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MysqlReadToolTest {
    private final MysqlReadClient client = mock(MysqlReadClient.class);
    private final LibraryStore library = mock(LibraryStore.class);
    private final ToolExecution.Binding binding = new ToolExecution.Binding("workspace", "approved-fixture", "mysql-read");
    private final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "synthetic-secret", "DISABLED");
    private final MysqlReadClient.Access access = new MysqlReadClient.Access("fixture", Map.of("records", Set.of("id")),
            2, 1024, Duration.ofSeconds(2));
    private final MysqlReadTool.Approval approval = new MysqlReadTool.Approval(binding, config, access);
    private final MysqlReadClient.Request request = new MysqlReadClient.Request("records", List.of("id"), Map.of(), 2);
    private final ToolExecution.Budget budget = new ToolExecution.Budget(5, Duration.ofSeconds(5), Duration.ofSeconds(2), 1024, 1024);

    @BeforeEach
    void prepare() {
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
    }

    @Test
    void approvedFactoryUsesOnlyExplicitConfigAndRejectsDuplicateInvocation() throws Exception {
        when(client.read(eq(config), eq(access), eq(request), any())).thenReturn(new MysqlReadClient.Result("[{\"id\":\"1\"}]", 1, false));
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request));
            assertEquals(OK, result.status());
            assertEquals("[{\"id\":\"1\"}]", result.output());
            assertEquals("duplicate_invocation", executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request)).reason());
        }
        verify(client).read(eq(config), eq(access), eq(request), any());
        verifyNoMoreInteractions(client);
        assertFalse(approval.toString().contains("synthetic-secret"));
    }

    @Test
    void wrongWorkspaceEnvironmentScopeAndToolAreDeniedWithoutConnecting() {
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            for (var other : List.of(new ToolExecution.Binding("other", "approved-fixture", "mysql-read"),
                    new ToolExecution.Binding("workspace", "production", "mysql-read"),
                    new ToolExecution.Binding("workspace", "approved-fixture", "shell"))) {
                assertEquals(DENIED, executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, other, request)).status());
            }
            assertEquals(DENIED, executor.execute(new ToolExecution.Invocation<>("shell", binding, request)).status());
        }
        verifyNoInteractions(client);
    }

    @Test
    void missingWorkspaceAndCancellationPreventDatabaseCalls() {
        when(library.workspace("workspace")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation()));
        prepare();
        var cancellation = new ToolExecution.Cancellation();
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, cancellation)) {
            cancellation.cancel();
            assertEquals(CANCELLED, executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request)).status());
        }
        verifyNoInteractions(client);
    }

    @Test
    void deletedWorkspaceFailsAlreadyOpenedSessionWithoutDatabaseCall() {
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            when(library.workspace("workspace")).thenReturn(Optional.empty());
            assertEquals(FAILED, executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request)).status());
        }
        verifyNoInteractions(client);
    }

    @Test
    void cancellationAfterReadDiscardsAllRows() throws Exception {
        var cancellation = new ToolExecution.Cancellation();
        when(client.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            cancellation.cancel();
            return new MysqlReadClient.Result("private row", 1, false);
        });
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, cancellation)) {
            var result = executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request));
            assertEquals(CANCELLED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void clientTruncationMarkerIsPreservedAndExecutorBudgetStillApplies() throws Exception {
        when(client.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("partial\n[TRUNCATED]\n", 0, true));
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            assertTrue(executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request)).output().contains("[TRUNCATED]"));
        }
        var tiny = new ToolExecution.Budget(1, Duration.ofSeconds(5), Duration.ofSeconds(2), 4, 4);
        try (var executor = MysqlReadTool.openApproved(client, library, approval, tiny, new ToolExecution.Cancellation())) {
            var result = executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request));
            assertEquals("part", result.output());
            assertTrue(result.truncated());
        }
    }

    @Test
    void emptyAndDatabaseFailureRemainDistinctAndErrorsAreNotExposed() throws Exception {
        when(client.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("", 0, false));
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request)).status());
        }
        when(client.read(any(), any(), any(), any())).thenThrow(new java.sql.SQLException("credentials private"));
        try (var executor = MysqlReadTool.openApproved(client, library, approval, budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(new ToolExecution.Invocation<>(MysqlReadTool.ID, binding, request));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
            assertEquals("tool_failed", result.reason());
        }
    }

    @Test
    void directToolCallCannotBypassApprovalBinding() {
        var tool = new MysqlReadTool(client, library, approval);
        var other = new ToolExecution.Binding("workspace", "production", "mysql-read");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(request,
                new ToolExecution.Context(other, () -> false), text -> fail()));
        verifyNoInteractions(client);
        assertThrows(IllegalArgumentException.class, () -> new MysqlReadTool.Approval(
                new ToolExecution.Binding("workspace", "*", "mysql-read"), config, access));
    }
}
