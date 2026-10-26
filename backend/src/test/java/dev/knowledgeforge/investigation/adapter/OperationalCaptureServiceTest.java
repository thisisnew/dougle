package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.knowledgeforge.attachment.adapter.JdbcAttachmentStore;
import dev.knowledgeforge.attachment.adapter.LocalAttachmentReader;
import dev.knowledgeforge.attachment.application.AttachmentService;
import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.knowledge.application.PublicationStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.search.adapter.JdbcEvidenceBundleStore;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.EvidenceBundleValidator;
import dev.knowledgeforge.search.application.EvidenceBundleWriter;
import dev.knowledgeforge.search.application.SearchCatalog;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class OperationalCaptureServiceTest {
    @TempDir Path root;
    private final MysqlInvestigationService mysql = mock(MysqlInvestigationService.class);
    private final DatadogInvestigationService datadog = mock(DatadogInvestigationService.class);
    private final LibraryStore library = mock(LibraryStore.class);
    private final DocumentAnalyzer analyzer = mock(DocumentAnalyzer.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");
    private final OperationalCaptureService.Input input = new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, "result");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AttachmentService attachments;
    private OperationalCaptureService service;

    @BeforeEach
    void prepare() {
        when(mysql.provenance(eq("workspace"), anyString())).thenReturn(new OperationalResultText.Text("{\"database\":\"fixture\",\"table\":\"receipts\"}", false));
        when(datadog.provenance(eq("workspace"), anyString())).thenReturn(new OperationalResultText.Text("{\"environment\":\"test\",\"service\":\"orders\",\"timezone\":\"UTC\"}", false));
        var data = new DriverManagerDataSource("jdbc:sqlite:" + root.resolve("capture.db"));
        jdbc = new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE question_attachment(id TEXT PRIMARY KEY, workspace_id TEXT, bundle_id TEXT, name TEXT, media_type TEXT, content BLOB, content_hash TEXT, extracted_text TEXT, truncated INTEGER, created_at TEXT)");
        jdbc.execute("CREATE TABLE evidence_bundle(id TEXT PRIMARY KEY,workspace_id TEXT,question TEXT,status TEXT,used_characters INTEGER,payload_json TEXT,digest TEXT,created_at TEXT)");
        transactions = new DataSourceTransactionManager(data);
        attachments = spy(new AttachmentService(new JdbcAttachmentStore(jdbc), new LocalAttachmentReader(analyzer), library));
        service = new OperationalCaptureService(mysql, datadog, attachments, library, clock, transactions);
        when(clock.instant()).thenReturn(now);
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
        when(library.workspace("other")).thenReturn(Optional.of(new Workspace("other", "other", "now")));
        when(mysql.result(eq("workspace"), anyString())).thenAnswer(invocation ->
                new MysqlInvestigationService.Result(invocation.getArgument(1), "SUCCEEDED", "[{\"status\":\"READY\"}]", 1, false, now.toString()));
        when(datadog.result("workspace", "result")).thenReturn(new DatadogInvestigationService.Result("result", "OK", "synthetic log", 1, false, now.toString()));
    }

    private OperationalCaptureService.Start start(OperationalCaptureService.Preview preview) {
        return new OperationalCaptureService.Start("synthetic-request-1234", preview.digest(), true);
    }
    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM question_attachment", Integer.class); }

    @Test
    void previewDoesNotPersistAndDuplicatePreviewIsSameExactMaterial() {
        var preview = service.preview("workspace", input);
        assertEquals(preview, service.preview("workspace", input));
        assertTrue(preview.sessionOnly());
        assertTrue(preview.text().length() <= 3000);
        assertEquals(preview.bytes(), preview.text().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("PREVIEW", service.get("workspace", preview.id()).status());
        assertEquals(0, count());
        verifyNoInteractions(analyzer, datadog);
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean());
    }

    @Test
    void captureStoresExactPreviewAndGetOrSameRequestNeverDuplicates() {
        var preview = service.preview("workspace", input);
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("CAPTURED", receipt.status());
        assertEquals(0, receipt.externalCalls());
        assertEquals(1, count());
        var stored = attachments.get("workspace", receipt.attachment().id());
        assertEquals(preview.text(), stored.text());
        assertArrayEquals(preview.text().getBytes(StandardCharsets.UTF_8), stored.content());
        assertEquals(ContentDigest.of(preview.text()), receipt.contentHash());
        assertNull(stored.bundleId());
        assertEquals(receipt, service.get("workspace", preview.id()));
        assertEquals(receipt, service.capture("workspace", preview.id(), start(preview)));
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(),
                new OperationalCaptureService.Start("different-request-1234", preview.digest(), true)));
        assertEquals(1, count());
        verifyNoInteractions(analyzer, datadog);
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean());
    }

    @Test
    void datadogCaptureIsLocalAndPreservesScopeAndTruncation() {
        when(datadog.result("workspace", "result")).thenReturn(new DatadogInvestigationService.Result("result", "OK", "가😀".repeat(2000), 1, true, now.toString()));
        var preview = service.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.DATADOG, "result"));
        assertTrue(preview.truncated());
        assertTrue(preview.text().contains("일부만 보관: 예"));
        assertTrue(preview.text().contains("\"environment\":\"test\""));
        assertTrue(preview.text().contains("\"timezone\":\"UTC\""));
        assertTrue(preview.text().length() <= 3000);
        assertEquals("CAPTURED", service.capture("workspace", preview.id(), start(preview)).status());
        verify(datadog, never()).execute(any(), any(), any(), anyBoolean());
        verifyNoInteractions(mysql, analyzer);
    }

    @Test
    void rejectsEmptyFailedCancelledRunningAndOversizedResults() {
        for (String status : List.of("EMPTY", "FAILED", "CANCELLED", "TIMEOUT", "PREVIEW", "RUNNING")) {
            when(mysql.result("workspace", "result")).thenReturn(new MysqlInvestigationService.Result("result", status, "untrusted", 0, false, now.toString()));
            assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        }
        for (String output : List.of("", " ", "x".repeat(16385))) {
            when(mysql.result("workspace", "result")).thenReturn(new MysqlInvestigationService.Result("result", "SUCCEEDED", output, 1, false, now.toString()));
            assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        }
        assertEquals(0, count());
    }

    @Test
    void consentDigestWorkspaceAndRequestIdentityAreMandatory() {
        var preview = service.preview("workspace", input);
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(), new OperationalCaptureService.Start("synthetic-request-1234", preview.digest(), false)));
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(), new OperationalCaptureService.Start("synthetic-request-1234", "changed", true)));
        assertThrows(LibraryException.class, () -> service.capture("other", preview.id(), start(preview)));
        assertThrows(LibraryException.class, () -> service.get("other", preview.id()));
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(), new OperationalCaptureService.Start("short", preview.digest(), true)));
        assertEquals(0, count());
    }

    @Test
    void changedResultCannotBeSubstitutedAfterPreview() {
        var preview = service.preview("workspace", input);
        when(mysql.result("workspace", "result")).thenReturn(new MysqlInvestigationService.Result("result", "SUCCEEDED", "changed", 1, false, now.toString()));
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("FAILED", receipt.status());
        assertNull(receipt.attachment());
        assertEquals(0, count());
    }

    @Test
    void changedScopeWithIdenticalRowsCannotBeSubstitutedAfterPreview() {
        var preview = service.preview("workspace", input);
        when(mysql.provenance("workspace", "result")).thenReturn(new OperationalResultText.Text("{\"database\":\"different\"}", false));
        assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("FAILED", receipt.status());
        assertEquals(0, count());
    }

    @Test
    void scopeChangeAfterUploadRollsBackActualAttachmentTransaction() {
        var preview = service.preview("workspace", input);
        doAnswer(invocation -> {
            Object uploaded = invocation.callRealMethod();
            when(mysql.provenance("workspace", "result")).thenReturn(new OperationalResultText.Text("changed scope", false));
            return uploaded;
        }).when(attachments).upload(anyString(), anyString(), any());
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("FAILED", receipt.status());
        assertNull(receipt.attachment());
        assertEquals(0, count());
    }

    @Test
    void missingServerProvenanceFailsClosed() {
        when(mysql.provenance("workspace", "result")).thenReturn(null);
        assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        assertEquals(0, count());
    }

    @Test
    void clippedScopeAndRowsPreserveExactReviewedBytesAndTruncationNotice() {
        var scope = OperationalResultText.provenance(java.util.Map.of("equalities", "가😀".repeat(1000)), List.of());
        when(mysql.provenance("workspace", "result")).thenReturn(scope);
        when(mysql.result("workspace", "result")).thenReturn(new MysqlInvestigationService.Result("result", "SUCCEEDED", "가😀".repeat(2000), 1, true, now.toString()));
        var preview = service.preview("workspace", input);
        assertTrue(preview.truncated());
        assertTrue(preview.text().contains("[TRUNCATED_SCOPE]"));
        assertTrue(preview.text().length() <= 3000);
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("CAPTURED", receipt.status());
        var stored = attachments.get("workspace", receipt.attachment().id());
        assertEquals(preview.text(), stored.text());
        assertArrayEquals(preview.text().getBytes(StandardCharsets.UTF_8), stored.content());
    }

    @Test
    void actualMysqlServiceScopeIsStoredAsExactReviewedAttachmentWithoutNetwork() throws Exception {
        var connections = mock(dev.knowledgeforge.plugins.adapter.PluginConnections.class);
        var reader = mock(dev.knowledgeforge.plugins.adapter.MysqlReadClient.class);
        var config = new dev.knowledgeforge.plugins.adapter.MysqlClient.Config("localhost", 3306, "fixture", "reader", "fictional-password", "DISABLED");
        when(connections.mysqlConfig()).thenReturn(config);
        when(reader.read(any(), any(), any(), any())).thenReturn(new dev.knowledgeforge.plugins.adapter.MysqlReadClient.Result("synthetic row", 1, false));
        try (var actual = new MysqlInvestigationService(connections, reader, library, clock)) {
            var query = new dev.knowledgeforge.plugins.adapter.MysqlReadClient.Request("receipts", java.util.List.of("id"), java.util.Map.of("id", config.password()), 10);
            var approved = actual.preview("workspace", query);
            actual.execute("workspace", approved.id(), "synthetic-query-1234", true);
            var capture = new OperationalCaptureService(actual, datadog, attachments, library, clock, transactions);
            var preview = capture.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, approved.id()));
            assertTrue(preview.text().contains("\"database\":\"fixture\""));
            assertTrue(preview.text().contains("\"table\":\"receipts\""));
            assertTrue(preview.text().contains("\"id\":\"[REDACTED]\""));
            assertFalse(preview.text().contains("localhost"));
            assertFalse(preview.text().contains(config.password()));
            var receipt = capture.capture("workspace", preview.id(), start(preview));
            assertEquals("CAPTURED", receipt.status());
            var stored = attachments.get("workspace", receipt.attachment().id());
            assertEquals(preview.text(), stored.text());
            assertArrayEquals(preview.text().getBytes(StandardCharsets.UTF_8), stored.content());
            assertEquals(ContentDigest.of(stored.content()), receipt.contentHash());
            verify(reader, times(1)).read(any(), any(), any(), any());
        }
    }

    @Test
    void actualDatadogServiceScopeIncludingUtcWindowSurvivesExactAttachmentCapture() {
        var connections = mock(dev.knowledgeforge.plugins.adapter.DatadogConnections.class);
        var client = mock(dev.knowledgeforge.plugins.adapter.DatadogLogsClient.class);
        var config = new dev.knowledgeforge.plugins.adapter.DatadogLogsClient.Config(dev.knowledgeforge.plugins.adapter.DatadogLogsClient.Site.EU1,
                "synthetic_api_key_0000", "synthetic_app_key_0000");
        when(connections.require("workspace", "fixture-revision")).thenReturn(config);
        when(client.read(any(), any(), any(), any())).thenReturn(new dev.knowledgeforge.plugins.adapter.DatadogLogsClient.Result("synthetic log", 1, false));
        var actual = new DatadogInvestigationService(connections, client, clock);
        var query = new DatadogInvestigationService.Query("fixture-revision", "test", "orders", java.util.Set.of("fixture-index"), now.minusSeconds(600), now, 20);
        var approved = actual.preview("workspace", query);
        actual.execute("workspace", approved.id(), "synthetic-query-1234", true);
        var capture = new OperationalCaptureService(mysql, actual, attachments, library, clock, transactions);
        var preview = capture.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.DATADOG, approved.id()));
        assertTrue(preview.text().contains("\"environment\":\"test\""));
        assertTrue(preview.text().contains("\"service\":\"orders\""));
        assertTrue(preview.text().contains("\"indexes\":[\"fixture-index\"]"));
        assertTrue(preview.text().contains("\"from\":\"2026-10-25T11:50:00Z\""));
        assertTrue(preview.text().contains("\"to\":\"2026-10-25T12:00:00Z\""));
        assertTrue(preview.text().contains("\"timezone\":\"UTC\""));
        assertFalse(preview.text().contains(config.apiKey()));
        assertFalse(preview.text().contains(config.applicationKey()));
        var receipt = capture.capture("workspace", preview.id(), start(preview));
        assertEquals("CAPTURED", receipt.status());
        var stored = attachments.get("workspace", receipt.attachment().id());
        assertArrayEquals(preview.text().getBytes(StandardCharsets.UTF_8), stored.content());
        assertEquals(preview.text(), stored.text());
        verify(client, times(1)).read(any(), any(), any(), any());
    }

    @Test
    void revocationAfterUploadRollsBackAndNeverReturnsAttachmentOrRawError() {
        var preview = service.preview("workspace", input);
        doAnswer(invocation -> {
            var uploaded = invocation.callRealMethod();
            when(mysql.result("workspace", "result")).thenThrow(new LibraryException(409, "private provider credentials"));
            return uploaded;
        }).when(attachments).upload(anyString(), anyString(), any());
        var receipt = service.capture("workspace", preview.id(), start(preview));
        assertEquals("FAILED", receipt.status());
        assertNull(receipt.attachment());
        assertEquals(0, count());
        assertFalse(receipt.toString().contains("private provider"));
        assertEquals(receipt, service.get("workspace", preview.id()));
        assertEquals(receipt, service.capture("workspace", preview.id(), start(preview)));
        verify(attachments).upload(anyString(), anyString(), any());
    }

    @Test
    void expiryDuringUploadRollsBackAndRestartCannotReplay() {
        var preview = service.preview("workspace", input);
        doAnswer(invocation -> {
            var uploaded = invocation.callRealMethod();
            when(clock.instant()).thenReturn(now.plusSeconds(300));
            return uploaded;
        }).when(attachments).upload(anyString(), anyString(), any());
        assertEquals("FAILED", service.capture("workspace", preview.id(), start(preview)).status());
        assertEquals(0, count());
        var restarted = new OperationalCaptureService(mysql, datadog, attachments, library, clock, transactions);
        assertThrows(LibraryException.class, () -> restarted.get("workspace", preview.id()));
    }

    @Test
    void capturedAttachmentSurvivesSourceRevocationButDeletedAttachmentIsNotRecreated() {
        var preview = service.preview("workspace", input);
        var saved = service.capture("workspace", preview.id(), start(preview));
        when(mysql.result("workspace", "result")).thenThrow(new LibraryException(409, "revoked"));
        assertEquals(saved, service.get("workspace", preview.id()));
        attachments.delete("workspace", saved.attachment().id());
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(), start(preview)));
        assertEquals(0, count());
    }

    @Test
    void concurrentCaptureAndReceiptRecoveryDoNotDuplicateWrites() throws Exception {
        var preview = service.preview("workspace", input);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(attachments).upload(anyString(), anyString(), any());
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var running = CompletableFuture.supplyAsync(() -> service.capture("workspace", preview.id(), start(preview)), workers);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("RUNNING", service.get("workspace", preview.id()).status());
                assertEquals("RUNNING", service.capture("workspace", preview.id(), start(preview)).status());
            } finally { release.countDown(); }
            assertEquals("CAPTURED", running.get(3, TimeUnit.SECONDS).status());
        }
        assertEquals(1, count());
        verify(attachments).upload(anyString(), anyString(), any());
    }

    @Test
    void requestIdCannotBeReusedForAnotherResultAndPendingCapacityIsBounded() {
        var first = service.preview("workspace", input);
        var second = service.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, "other-result"));
        service.capture("workspace", first.id(), start(first));
        assertThrows(LibraryException.class, () -> service.capture("workspace", second.id(), start(second)));
        for (int count = 2; count < 32; count++) service.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, "result-" + count));
        assertEquals(429, assertThrows(LibraryException.class, () -> service.preview("workspace",
                new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, "overflow"))).status());
    }

    @Test
    void savedCaptureBindsToNewImmutableBundleAndPassesExistingValidatorWithoutAi() {
        var preview = service.preview("workspace", input);
        var receipt = service.capture("workspace", preview.id(), start(preview));
        var knowledge = mock(KnowledgeStore.class);
        var publications = mock(PublicationStore.class);
        var catalog = mock(SearchCatalog.class);
        var search = mock(SearchService.class);
        var bundleStore = new JdbcEvidenceBundleStore(jdbc);
        var writer = new EvidenceBundleWriter(knowledge, publications, library, bundleStore, catalog, attachments);
        var bundles = new EvidenceBundleService(search, writer, bundleStore, library, attachments);
        var validator = new EvidenceBundleValidator(bundles, knowledge, publications, catalog, attachments);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM evidence_bundle", Integer.class));
        var bundle = new TransactionTemplate(transactions).execute(status -> writer.capture("workspace", "조회 결과를 설명해 주세요", List.of(), false,
                List.of(), List.of(receipt.attachment().id())));
        assertNotNull(bundle);
        assertEquals("ATTACHMENT_TEXT", bundle.payload().items().getFirst().kind());
        assertEquals(preview.text(), bundle.payload().items().getFirst().quote());
        assertTrue(bundle.payload().items().getFirst().quote().contains("\"database\":\"fixture\""));
        assertTrue(bundle.payload().items().getFirst().quote().contains("\"table\":\"receipts\""));
        assertEquals(bundle.id(), attachments.get("workspace", receipt.attachment().id()).bundleId());
        assertTrue(validator.check("workspace", bundle.id()).eligible());
        String digest = bundle.digest();
        assertThrows(LibraryException.class, () -> attachments.pending("workspace", List.of(receipt.attachment().id())));
        assertEquals(digest, bundles.get("workspace", bundle.id()).digest());
        verifyNoInteractions(search, knowledge, publications, catalog, analyzer);
    }
}
