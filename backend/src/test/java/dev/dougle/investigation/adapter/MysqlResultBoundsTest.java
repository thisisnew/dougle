package dev.dougle.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.plugins.adapter.MysqlClient;
import dev.dougle.plugins.adapter.MysqlReadClient;
import dev.dougle.plugins.adapter.PluginConnections;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MysqlResultBoundsTest {
    @Test
    void expandedSecretMaskingStaysBoundedInBothImmediateAndRecoveredResults() throws Exception {
        var connections = mock(PluginConnections.class);
        var reader = mock(MysqlReadClient.class);
        var library = mock(LibraryStore.class);
        var config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "x", "DISABLED");
        when(connections.mysqlConfig()).thenReturn(config);
        when(connections.mysqlRevision()).thenReturn("fixture-revision");
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("x".repeat(16000), 1, false));
        var service = new MysqlInvestigationService(connections, reader, library);
        var preview = service.preview("workspace", new MysqlReadClient.Request("records", List.of("body"), Map.of("id", "1"), 1));
        var result = service.execute("workspace", preview.id(), "synthetic-request-1234", true);
        assertEquals("SUCCEEDED", result.status());
        assertTrue(result.truncated());
        assertTrue(result.output().getBytes(StandardCharsets.UTF_8).length <= 16384);
        assertFalse(result.output().contains("x"));
        assertEquals(result, service.result("workspace", preview.id()));
        assertEquals(result, service.execute("workspace", preview.id(), "synthetic-request-1234", true));
        verify(reader).read(any(), any(), any(), any());
    }
}
