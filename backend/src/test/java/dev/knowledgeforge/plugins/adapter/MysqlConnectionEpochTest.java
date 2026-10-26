package dev.knowledgeforge.plugins.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class MysqlConnectionEpochTest {
    private final PluginVault vault = mock(PluginVault.class);
    private final MysqlClient mysql = mock(MysqlClient.class);
    private final PluginConnections connections = new PluginConnections(vault, mock(NotionClient.class), mysql);
    private final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "synthetic-password", "DISABLED");

    @Test
    void epochChangesOnEverySaveDisconnectAndFailedMutationButNotReadOrTest() {
        long first = connections.mysqlEpoch();
        connections.mysql(config, false);
        assertEquals(first, connections.mysqlEpoch());
        connections.mysql(config, true);
        long saved = connections.mysqlEpoch();
        assertTrue(saved > first);
        connections.mysql(config, true);
        assertTrue(connections.mysqlEpoch() > saved);
        long beforeDisconnect = connections.mysqlEpoch();
        connections.disconnect("mysql");
        assertTrue(connections.mysqlEpoch() > beforeDisconnect);
        long beforeFailure = connections.mysqlEpoch();
        doThrow(new IllegalStateException("synthetic storage failure")).when(vault).put(eq("mysql-revision"), anyString());
        assertThrows(IllegalStateException.class, () -> connections.mysql(config, true));
        assertTrue(connections.mysqlEpoch() > beforeFailure);
        clearInvocations(vault);
        connections.mysqlEpoch();
        verifyNoInteractions(vault);
    }

    @Test
    void checkpointEpochDoesNotWaitForConnectionMonitorOrVaultWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return null;
        }).when(vault).put(eq("mysql-revision"), anyString());
        long old = connections.mysqlEpoch();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var saving = CompletableFuture.runAsync(() -> connections.mysql(config, true), workers);
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var observed = CompletableFuture.supplyAsync(connections::mysqlEpoch, workers);
                assertTrue(observed.get(200, TimeUnit.MILLISECONDS) > old);
            } finally { release.countDown(); }
            saving.get(1, TimeUnit.SECONDS);
        }
    }
}
