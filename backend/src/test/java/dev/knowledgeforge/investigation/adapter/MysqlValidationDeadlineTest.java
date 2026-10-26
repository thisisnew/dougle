package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import dev.knowledgeforge.plugins.adapter.PluginConnections;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(10)
class MysqlValidationDeadlineTest {
    @TempDir Path root;
    private final PluginConnections connections = mock(PluginConnections.class);
    private final MysqlReadClient reader = mock(MysqlReadClient.class);
    private final LibraryStore library = mock(LibraryStore.class);
    private final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "synthetic-password", "DISABLED");
    private final MysqlReadClient.Request query = new MysqlReadClient.Request("records", List.of("status"), Map.of("id", "1"), 1);
    private final Workspace workspace = new Workspace("workspace", "fixture", "now");

    @BeforeEach
    void prepare() throws Exception {
        when(connections.mysqlConfig()).thenReturn(config);
        when(connections.mysqlRevision()).thenReturn("revision");
        when(library.workspace("workspace")).thenReturn(Optional.of(workspace));
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("safe", 1, false));
    }

    private MysqlInvestigationService service(Duration validation, Duration execution) {
        return new MysqlInvestigationService(connections, reader, library, Clock.systemUTC(), validation, execution);
    }

    @Test
    void thousandsOfCharacterChecksNeverReadWorkspaceOrVault() throws Exception {
        try (var service = service(Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            var preview = service.preview("workspace", query);
            clearInvocations(library, connections);
            when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
                verify(library).workspace("workspace");
                verify(connections).mysqlConfig();
                verify(connections).mysqlRevision();
                clearInvocations(library, connections);
                Runnable checkpoint = invocation.getArgument(3);
                for (int count = 0; count < 10000; count++) checkpoint.run();
                verifyNoInteractions(library);
                verify(connections, never()).mysqlConfig();
                verify(connections, never()).mysqlRevision();
                return new MysqlReadClient.Result("safe", 1, false);
            });
            assertEquals("SUCCEEDED", service.execute("workspace", preview.id(), "synthetic-request-1234", true).status());
            verify(library).workspace("workspace");
            verify(connections).mysqlConfig();
            verify(connections).mysqlRevision();
        }
    }

    @Test
    void connectionEpochCancelsDuringReadWithoutVaultLookup() throws Exception {
        try (var service = service(Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            var preview = service.preview("workspace", query);
            when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
                when(connections.mysqlEpoch()).thenReturn(2L);
                clearInvocations(connections, library);
                invocation.getArgument(3, Runnable.class).run();
                fail("Revoked read must stop");
                return null;
            });
            var result = service.execute("workspace", preview.id(), "synthetic-request-1234", true);
            assertEquals("CANCELLED", result.status());
            assertEquals("", result.output());
            verifyNoInteractions(library);
            verify(connections, never()).mysqlConfig();
            verify(connections, never()).mysqlRevision();
        }
    }

    @Test
    void postReadWorkspaceRevalidationStillDiscardsDeletedWorkspaceOutput() throws Exception {
        try (var service = service(Duration.ofSeconds(1), Duration.ofSeconds(5))) {
            var preview = service.preview("workspace", query);
            when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
                when(library.workspace("workspace")).thenReturn(Optional.empty());
                return new MysqlReadClient.Result("private", 1, false);
            });
            var result = service.execute("workspace", preview.id(), "synthetic-request-1234", true);
            assertEquals("FAILED", result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void actualSaturatedPoolWaitIsBoundedAndCancellationNeedsNoDatabase() throws Exception {
        var settings = new HikariConfig();
        settings.setJdbcUrl("jdbc:sqlite:" + root.resolve("pool.db"));
        settings.setMaximumPoolSize(1);
        try (var data = new HikariDataSource(settings);
             var service = service(Duration.ofMillis(150), Duration.ofSeconds(2))) {
            var preview = service.preview("workspace", query);
            var exited = new CountDownLatch(1);
            when(library.workspace("workspace")).thenAnswer(invocation -> {
                try (var acquired = data.getConnection()) { return Optional.of(workspace); }
                finally { exited.countDown(); }
            });
            try (var held = data.getConnection()) {
                long started = System.nanoTime();
                assertEquals(504, assertThrows(LibraryException.class, () -> service.result("workspace", preview.id())).status());
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
                assertTrue(exited.await(1, TimeUnit.SECONDS));
                clearInvocations(library);
                service.cancel("workspace", preview.id());
                verifyNoInteractions(library);
                assertFalse(held.isClosed());
                assertEquals(30000, data.getConnectionTimeout());
            }
        }
    }

    @Test
    void captureTransactionReusesBoundConnectionRatherThanDeadlockingAnotherWorker() {
        var settings = new HikariConfig();
        settings.setJdbcUrl("jdbc:sqlite:" + root.resolve("transaction.db"));
        settings.setMaximumPoolSize(1);
        try (var data = new HikariDataSource(settings);
             var service = service(Duration.ofMillis(150), Duration.ofSeconds(2))) {
            var jdbc = new JdbcTemplate(data);
            var preview = service.preview("workspace", query);
            var caller = Thread.currentThread();
            when(library.workspace("workspace")).thenAnswer(invocation -> {
                assertSame(caller, Thread.currentThread());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
                return Optional.of(workspace);
            });
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
            transaction.setTimeout(2);
            transaction.executeWithoutResult(status -> assertEquals("PREVIEW", service.result("workspace", preview.id()).status()));
        }
    }

    @Test
    void cancellationInterruptsPostReadValidationAndNeverReturnsRows() throws Exception {
        try (var service = service(Duration.ofSeconds(2), Duration.ofSeconds(5));
             var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var preview = service.preview("workspace", query);
            var entered = new CountDownLatch(1);
            var stopped = new CountDownLatch(1);
            when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
                when(library.workspace("workspace")).thenAnswer(check -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); return Optional.of(workspace); }
                    finally { stopped.countDown(); }
                });
                return new MysqlReadClient.Result("private", 1, false);
            });
            var running = CompletableFuture.supplyAsync(() -> service.execute("workspace", preview.id(), "synthetic-request-1234", true), workers);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.cancel("workspace", preview.id());
            var result = running.get(1, TimeUnit.SECONDS);
            assertEquals("CANCELLED", result.status());
            assertEquals("", result.output());
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void validationConsumesTheSameReadBudgetAndLateResultsAreTimeout() throws Exception {
        try (var service = service(Duration.ofMillis(500), Duration.ofMillis(400))) {
            var preview = service.preview("workspace", query);
            when(connections.mysqlConfig()).thenAnswer(invocation -> {
                Thread.sleep(80);
                return config;
            });
            when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
                var access = invocation.getArgument(1, MysqlReadClient.Access.class);
                assertTrue(access.timeout().compareTo(Duration.ofMillis(350)) < 0);
                Thread.sleep(410);
                invocation.getArgument(3, Runnable.class).run();
                return new MysqlReadClient.Result("late", 1, false);
            });
            var result = service.execute("workspace", preview.id(), "synthetic-request-1234", true);
            assertEquals("TIMEOUT", result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void uncooperativeValidationHasBoundedWorkersAndNoUnboundedQueue() throws Exception {
        try (var service = service(Duration.ofMillis(80), Duration.ofSeconds(2))) {
            var preview = service.preview("workspace", query);
            var release = new AtomicBoolean();
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            var exited = new CountDownLatch(2);
            when(library.workspace("workspace")).thenAnswer(invocation -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    while (!release.get()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                        Thread.interrupted();
                    }
                    return Optional.of(workspace);
                } finally { active.decrementAndGet(); exited.countDown(); }
            });
            try {
                for (int count = 0; count < 5; count++) {
                    var error = assertThrows(LibraryException.class, () -> service.result("workspace", preview.id()));
                    assertTrue(error.status() == 504 || error.status() == 503);
                }
                assertEquals(2, maximum.get());
            } finally { release.set(true); }
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }
}
