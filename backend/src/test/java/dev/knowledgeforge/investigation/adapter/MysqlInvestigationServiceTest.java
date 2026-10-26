package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import dev.knowledgeforge.plugins.adapter.PluginConnections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlInvestigationServiceTest {
    final PluginConnections connections = mock(PluginConnections.class);
    final MysqlReadClient reader = mock(MysqlReadClient.class);
    final LibraryStore library = mock(LibraryStore.class);
    final MysqlInvestigationService service = new MysqlInvestigationService(connections, reader, library);
    final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "fictional-password", "DISABLED");
    final MysqlReadClient.Request query = new MysqlReadClient.Request("receipts", List.of("id", "status"), Map.of("id", "fixture-1"), 10);

    @BeforeEach void prepare() {
        when(connections.mysqlConfig()).thenReturn(config);
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
    }

    @org.junit.jupiter.api.AfterEach void closeService() { service.close(); }

    @Test void revokedScopeBeforeDispatchNeverCallsReader() throws Exception {
        var preview = service.preview("workspace", query);
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true,
                () -> { throw new LibraryException(409, "scope revoked"); })).isInstanceOf(LibraryException.class);
        verify(reader, never()).read(any(), any(), any(), any());
    }

    @Test void scopeRevocationWithinReadStopsFurtherWorkAndSuppressesOutput() throws Exception {
        var preview = service.preview("workspace", query);
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        var afterCheckpoint = new java.util.concurrent.atomic.AtomicBoolean();
        when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            Runnable checkpoint = invocation.getArgument(3);
            checkpoint.run();
            revoked.set(true);
            checkpoint.run();
            afterCheckpoint.set(true);
            return new MysqlReadClient.Result("must not escape", 1, false);
        });
        var result = service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true, () -> {
            if (revoked.get()) throw new java.util.concurrent.CancellationException();
        });
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.output()).isEmpty();
        assertThat(afterCheckpoint).isFalse();
    }

    @Test void lateReadResultAfterScopeRevocationIsNotPublishedOrReexecuted() throws Exception {
        var preview = service.preview("workspace", query);
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            revoked.set(true);
            return new MysqlReadClient.Result("private late result", 1, false);
        });
        String requestId = UUID.randomUUID().toString();
        var result = service.execute("workspace", preview.id(), requestId, true, () -> {
            if (revoked.get()) throw new LibraryException(409, "revoked");
        });
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.output()).isEmpty();
        assertThat(service.execute("workspace", preview.id(), requestId, true)).isEqualTo(result);
        verify(reader, times(1)).read(any(), any(), any(), any());
    }

    @Test void scopeChecksCanAcquirePendingMonitorWithoutDeadlock() throws Exception {
        var preview = service.preview("workspace", query);
        Object monitor = org.springframework.test.util.ReflectionTestUtils.getField(service, "pending");
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(3)).run();
            return new MysqlReadClient.Result("safe", 1, false);
        });
        var result = service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true, () -> {
            synchronized (monitor) { checks.incrementAndGet(); }
        });
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(checks.get()).isGreaterThanOrEqualTo(5);
    }

    @Test void blockedScopeCheckTimesOutWithoutDispatchAndWorkerIsInterrupted() throws Exception {
        var exited = new java.util.concurrent.CountDownLatch(1);
        try (var bounded = new MysqlInvestigationService(connections, reader, library, java.time.Clock.systemUTC(),
                java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(2))) {
            var preview = bounded.preview("workspace", query);
            assertThatThrownBy(() -> bounded.execute("workspace", preview.id(), UUID.randomUUID().toString(), true, () -> {
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exited.countDown(); }
            })).isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(504));
            assertThat(exited.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(bounded.result("workspace", preview.id()).status()).isEqualTo("PREVIEW");
            verify(reader, never()).read(any(), any(), any(), any());
        }
    }

    @Test void previewDoesNotReadRowsOrExposePassword() throws Exception {
        var preview = service.preview("workspace", query);
        assertThat(preview.connection()).isEqualTo("localhost:3306");
        assertThat(preview.toString()).doesNotContain("fictional-password");
        assertThat(preview.maxBytes()).isEqualTo(16384);
        verify(reader, never()).read(any(), any(), any(), any());
    }

    @Test void provenanceUsesApprovedQueryWithoutHostOrCredentialsAndRevalidatesConnection() {
        var sensitive = new MysqlReadClient.Request("receipts", List.of("id", "status"), Map.of("id", config.password()), 10);
        var preview = service.preview("workspace", sensitive);
        var scope = service.provenance("workspace", preview.id());
        assertThat(scope.text()).contains("\"database\":\"fixture\"", "\"table\":\"receipts\"", "\"columns\":[\"id\",\"status\"]",
                "\"id\":\"[REDACTED]\"", "\"limit\":10", "DB 세션 시간대 미기록");
        assertThat(scope.text()).doesNotContain(config.password(), "localhost", "3306", "\"reader\"");
        assertThat(scope.truncated()).isFalse();
        when(connections.mysqlEpoch()).thenReturn(2L);
        assertThatThrownBy(() -> service.provenance("workspace", preview.id())).isInstanceOf(LibraryException.class);
    }

    @Test void explicitConsentAndSameRequestAreExactlyOnce() throws Exception {
        var preview = service.preview("workspace", query);
        String requestId = UUID.randomUUID().toString();
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("id\tstatus\nfixture-1\tREADY", 1, false));
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), requestId, false)).isInstanceOf(LibraryException.class);
        var result = service.execute("workspace", preview.id(), requestId, true);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(service.execute("workspace", preview.id(), requestId, true)).isEqualTo(result);
        assertThat(service.result("workspace", preview.id())).isEqualTo(result);
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true)).isInstanceOf(LibraryException.class);
        verify(reader, times(1)).read(eq(config), any(), eq(query), any());
    }

    @Test void masksKnownConnectionSecretInReturnedRowsAndReceipts() throws Exception {
        var preview = service.preview("workspace", query);
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("status\nfictional-password", 1, false));
        String requestId = UUID.randomUUID().toString();
        var result = service.execute("workspace", preview.id(), requestId, true);
        assertThat(result.output()).isEqualTo("status\n[REDACTED]");
        assertThat(service.result("workspace", preview.id()).output()).doesNotContain(config.password());
        assertThat(service.execute("workspace", preview.id(), requestId, true)).isEqualTo(result);
        verify(reader, times(1)).read(any(), any(), any(), any());
    }

    @Test void cannotSelectCredentialColumnsOrAllRows() {
        for (String name : List.of("password_hash", "access_token", "client_secret", "API_KEY", "apiKey", "privateKey", "authorization")) {
            assertThatThrownBy(() -> service.preview("workspace", new MysqlReadClient.Request("accounts", List.of(name), Map.of("id", "1"), 1)))
                    .isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> service.preview("workspace", new MysqlReadClient.Request("receipts", List.of("id"), Map.of(), 1)))
                .isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> service.preview("workspace", new MysqlReadClient.Request("receipts", List.of("id"), Map.of("id", "1"), 51)))
                .isInstanceOf(LibraryException.class);
        verifyNoInteractions(reader);
    }

    @Test void changedConnectionWorkspaceAndCancelledPreviewCannotRun() throws Exception {
        var preview = service.preview("workspace", query);
        when(connections.mysqlConfig()).thenReturn(new MysqlClient.Config("other.invalid", 3306, "fixture", "reader", "changed", "REQUIRED"));
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true)).isInstanceOf(LibraryException.class);
        when(connections.mysqlConfig()).thenReturn(config);
        when(library.workspace("other")).thenReturn(Optional.of(new Workspace("other", "other", "now")));
        assertThatThrownBy(() -> service.execute("other", preview.id(), UUID.randomUUID().toString(), true)).isInstanceOf(LibraryException.class);
        service.cancel("workspace", preview.id());
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true)).isInstanceOf(LibraryException.class);
        verify(reader, never()).read(any(), any(), any(), any());
    }

    @Test void lateResultAfterCancellationIsDiscardedAndNeverReplayed() throws Exception {
        var preview = service.preview("workspace", query);
        when(reader.read(any(), any(), any(), any())).thenAnswer(invocation -> {
            service.cancel("workspace", preview.id());
            return new MysqlReadClient.Result("late private row", 1, false);
        });
        String requestId = UUID.randomUUID().toString();
        var result = service.execute("workspace", preview.id(), requestId, true);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.output()).isEmpty();
        assertThat(service.execute("workspace", preview.id(), requestId, true)).isEqualTo(result);
        verify(reader, times(1)).read(any(), any(), any(), any());
    }

    @Test void emptyTimeoutAndFailureAreDistinctAndErrorsDoNotLeak() throws Exception {
        when(reader.read(any(), any(), any(), any()))
                .thenReturn(new MysqlReadClient.Result("", 0, false))
                .thenThrow(new java.sql.SQLTimeoutException("sensitive host"))
                .thenThrow(new java.sql.SQLException("password=fictional-password"));
        for (String status : List.of("EMPTY", "TIMEOUT", "FAILED")) {
            var preview = service.preview("workspace", query);
            var result = service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true);
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.output()).isEmpty();
            assertThat(result.toString()).doesNotContain("sensitive", "fictional-password");
        }
    }

    @Test void restartCannotReplayAnOldApproval() {
        var preview = service.preview("workspace", query);
        var restarted = new MysqlInvestigationService(connections, reader, library);
        assertThatThrownBy(() -> restarted.execute("workspace", preview.id(), UUID.randomUUID().toString(), true)).isInstanceOf(LibraryException.class);
    }

    @Test void expiredReceiptsCannotBeReadOrReplayedWithoutAnotherPreviewCall() throws Exception {
        var clock = mock(java.time.Clock.class);
        var now = java.time.Instant.parse("2026-10-25T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        var timed = new MysqlInvestigationService(connections, reader, library, clock);
        var first = timed.preview("workspace", query);
        var second = timed.preview("workspace", query);
        String requestId = UUID.randomUUID().toString();
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("private row", 1, false));
        timed.execute("workspace", first.id(), requestId, true);
        timed.execute("workspace", second.id(), requestId, true);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertThatThrownBy(() -> timed.result("workspace", first.id())).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> timed.execute("workspace", second.id(), requestId, true)).isInstanceOf(LibraryException.class);
        verify(reader, times(2)).read(any(), any(), any(), any());
    }

    @Test void identicalCredentialsAfterReconnectStillInvalidateApproval() throws Exception {
        when(connections.mysqlRevision()).thenReturn("connection-one");
        var preview = service.preview("workspace", query);
        when(connections.mysqlRevision()).thenReturn("connection-two");
        assertThatThrownBy(() -> service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true))
                .isInstanceOf(LibraryException.class);
        verify(reader, never()).read(any(), any(), any(), any());
    }

    @Test void partialFirstRowIsNotReportedAsEmpty() throws Exception {
        var preview = service.preview("workspace", query);
        when(reader.read(any(), any(), any(), any())).thenReturn(new MysqlReadClient.Result("[{\"id\":\"partial", 0, true));
        var result = service.execute("workspace", preview.id(), UUID.randomUUID().toString(), true);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.truncated()).isTrue();
    }
}
