package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.adapter.BrowserToolExecutorFactory;
import dev.knowledgeforge.investigation.adapter.JdbcBrowserInvestigationStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserInvestigationServiceTest {
    private SingleConnectionDataSource data;
    private JdbcTemplate jdbc;
    private JdbcBrowserInvestigationStore store;
    private BrowserCaptureTransport transport;
    private BrowserInvestigationService service;
    private final BrowserCaptureTransport.Snapshot snapshot = new BrowserCaptureTransport.Snapshot(
            "https://example.com/app", Instant.now().toString(), "Fixture", "Rendered fixture", List.of(), false, "CAPTURED");

    @BeforeEach void setup() throws Exception {
        data = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(data);
        jdbc.execute("""
                CREATE TABLE browser_investigation(digest TEXT PRIMARY KEY,workspace_id TEXT NOT NULL,bundle_id TEXT NOT NULL,
                bundle_digest TEXT NOT NULL,scope_json TEXT NOT NULL,created_at TEXT NOT NULL,expires_at TEXT NOT NULL,
                request_id TEXT,approved_at TEXT,updated_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PREVIEW',
                result_json TEXT,error TEXT,UNIQUE(workspace_id,request_id))
                """);
        store = new JdbcBrowserInvestigationStore(jdbc);
        transport = mock(BrowserCaptureTransport.class);
        when(transport.capability()).thenReturn(new BrowserCaptureTransport.Capability("AVAILABLE", "fixture"));
        when(transport.capture(any(), any())).thenReturn(snapshot);
        var bundles = mock(EvidenceBundleService.class);
        var bundle = mock(EvidenceBundle.class);
        when(bundle.id()).thenReturn("bundle");
        when(bundle.digest()).thenReturn("immutable-digest");
        when(bundles.get("workspace", "bundle")).thenReturn(bundle);
        service = new BrowserInvestigationService(store, new BrowserToolExecutorFactory(transport), transport, bundles,
                new DataSourceTransactionManager(data));
    }

    @AfterEach void cleanup() throws Exception {
        service.close();
        awaitIdle();
        data.destroy();
    }

    private BrowserInvestigationService.Preview preview() {
        return service.preview("workspace", new BrowserInvestigationService.Request("bundle", snapshot.url(), List.of("/app", "/api/status")));
    }

    private BrowserInvestigationService.Start request(BrowserInvestigationService.Preview preview) {
        return new BrowserInvestigationService.Start(UUID.randomUUID().toString(), "bundle", preview.digest(), true);
    }

    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ReflectionTestUtils.getField(service, "worker") != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(ReflectionTestUtils.getField(service, "worker")).isNull();
    }

    @Test void previewBindsScopeAndNeverLaunchesBrowser() throws Exception {
        var preview = preview();
        assertThat(preview.maxAiCalls()).isZero();
        assertThat(preview.digest()).isNotEqualTo(preview().digest());
        assertThat(store.preview("workspace", preview.digest()).orElseThrow().scope()).isEqualTo(preview.scope());
        verify(transport, never()).capture(any(), any());
    }

    @Test void consumesConsentBeforeTransportAndSameIdRetriesNeverReplay() throws Exception {
        var preview = preview();
        var request = request(preview);
        when(transport.capture(any(), any())).thenAnswer(call -> {
            assertThat(store.find("workspace", request.requestId()).orElseThrow().status()).isEqualTo("RUNNING");
            return snapshot;
        });
        service.start("workspace", request);
        awaitIdle();
        var completed = service.get("workspace", request.requestId());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.result()).isEqualTo(snapshot);
        assertThat(service.start("workspace", request)).isEqualTo(completed);
        assertThatThrownBy(() -> service.start("workspace", request(preview))).isInstanceOf(LibraryException.class);
        verify(transport, times(1)).capture(any(), any());
    }

    @Test void rejectsMissingConsentExpiredPreviewAndChangedRequestBinding() throws Exception {
        var preview = preview();
        assertThatThrownBy(() -> service.start("workspace", new BrowserInvestigationService.Start(
                UUID.randomUUID().toString(), "bundle", preview.digest(), false))).isInstanceOf(LibraryException.class);
        jdbc.update("UPDATE browser_investigation SET expires_at='2000-01-01T00:00:00Z'");
        assertThatThrownBy(() -> service.start("workspace", request(preview))).isInstanceOf(LibraryException.class);
        var fresh = request(preview());
        service.start("workspace", fresh);
        awaitIdle();
        assertThatThrownBy(() -> service.start("workspace", new BrowserInvestigationService.Start(
                fresh.requestId(), "other-bundle", fresh.digest(), true))).isInstanceOf(LibraryException.class);
        verify(transport, times(1)).capture(any(), any());
    }

    @Test void restartFailsPendingJobsWithoutReplayAndListsOldestFirst() {
        var first = preview();
        var second = preview();
        store.consume("workspace", first.digest(), "first-request-1234", "2026-01-01T00:00:00Z");
        store.consume("workspace", second.digest(), "second-request-123", "2026-01-01T00:00:01Z");
        var recovered = new JdbcBrowserInvestigationStore(jdbc);
        assertThat(recovered.list("workspace", "bundle")).extracting(BrowserInvestigationStore.Job::id)
                .containsExactly("first-request-1234", "second-request-123");
        assertThat(recovered.list("workspace", "bundle")).allMatch(job -> job.status().equals("FAILED") && job.result() == null);
        assertThat(recovered.consume("workspace", first.digest(), "new-request-12345", Instant.now().toString())).isFalse();
    }

    @Test void cancellationKeepsSlotUntilTransportStopsAndDiscardsLateResults() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(transport.capture(any(), any())).thenAnswer(call -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { }
            }
            return snapshot;
        });
        var request = request(preview());
        try {
            service.start("workspace", request);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel("workspace", request.requestId()).status()).isEqualTo("CANCELLING");
            assertThatThrownBy(() -> service.start("workspace", request(preview()))).isInstanceOf(LibraryException.class);
        } finally { release.countDown(); }
        awaitIdle();
        assertThat(service.get("workspace", request.requestId()).status()).isEqualTo("CANCELLED");
        assertThat(service.get("workspace", request.requestId()).result()).isNull();
    }

    @Test void missingSchemaReportsSetupWithoutConstructorDdl() {
        jdbc.execute("DROP TABLE browser_investigation");
        var missing = new JdbcBrowserInvestigationStore(jdbc);
        assertThat(missing.available()).isFalse();
        assertThat(service.capability().status()).isEqualTo("SETUP_REQUIRED");
    }
}
