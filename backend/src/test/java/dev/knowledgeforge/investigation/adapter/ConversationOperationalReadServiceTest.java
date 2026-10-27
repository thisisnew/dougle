package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.knowledgeforge.attachment.domain.QuestionAttachment;
import dev.knowledgeforge.attachment.application.AttachmentService;
import dev.knowledgeforge.investigation.application.ConversationOperationalScopeAccess;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConversationOperationalReadServiceTest {
    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(now);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    };
    private final ConversationOperationalScopeAccess scopes = mock(ConversationOperationalScopeAccess.class);
    private final MysqlInvestigationService mysql = mock(MysqlInvestigationService.class);
    private final OperationalCaptureService captures = mock(OperationalCaptureService.class);
    private final AttachmentService attachments = mock(AttachmentService.class);
    private final ConversationOperationalReadService service = new ConversationOperationalReadService(scopes, mysql, captures, attachments, clock);
    private final PendingOperationalScope.Mysql candidate = new PendingOperationalScope.Mysql("receipts", List.of("id", "status"), Map.of("id", "SYN-1"), 10);
    private final AtomicReference<PendingOperationalScope> current = new AtomicReference<>();
    private final ConversationOperationalReadService.Input input = new ConversationOperationalReadService.Input("scope", 1);

    @BeforeEach
    void prepare() {
        current.set(scope(candidate, "generation", "parent", 1));
        when(scopes.requireCurrent("workspace", "scope")).thenAnswer(invocation -> current.get());
        when(mysql.preview(eq("workspace"), any())).thenAnswer(invocation -> new MysqlInvestigationService.Preview(
                "mysql-preview", "workspace", "127.0.0.1:3306", "fixture", invocation.getArgument(1), now.plusSeconds(600).toString(), 16384, 10));
        when(mysql.execute(eq("workspace"), eq("mysql-preview"), anyString(), eq(true), any(Runnable.class))).thenReturn(
                new MysqlInvestigationService.Result("mysql-preview", "SUCCEEDED", "id\tstatus\nSYN-1\tREADY", 1, false, now.toString()));
    }

    @AfterEach
    void close() { service.close(); }

    @Test
    void authoritativeCandidateCreatesBoundPreviewWithoutReadOrAi() {
        var preview = service.preview("workspace", input);
        assertEquals("thread", preview.threadId());
        assertEquals("parent", preview.parentBundleId());
        assertEquals(OperationalTool.MYSQL_READ, preview.tool());
        assertTrue(preview.queryPreview().contains("SYN-1"));
        assertEquals(10, preview.maxRows());
        assertEquals(16384, preview.maxBytes());
        assertTrue(preview.sessionOnly());
        assertEquals(preview, service.preview("workspace", input));
        verify(mysql).preview("workspace", new MysqlReadClient.Request("receipts", List.of("id", "status"), Map.of("id", "SYN-1"), 10));
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
        verifyNoInteractions(captures);
    }

    @Test
    void incompleteOrUnsafeFullTableCandidatesNeverReachTransportPreview() {
        for (var value : List.of(new PendingOperationalScope.Mysql(null, null, null, null),
                new PendingOperationalScope.Mysql("receipts", List.of("id"), Map.of(), 10),
                new PendingOperationalScope.Mysql("receipts", List.of("id"), Map.of("id", "SYN-1"), 51))) {
            current.set(scope(value, "generation", "parent", 1));
            assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        }
        verifyNoInteractions(mysql, captures);
    }

    @Test
    void mismatchedScopeRevisionWorkspaceOrExpiredScopeIsRejected() {
        assertThrows(LibraryException.class, () -> service.preview("workspace", new ConversationOperationalReadService.Input("scope", 2)));
        when(scopes.requireCurrent("other", "scope")).thenReturn(current.get());
        assertThrows(LibraryException.class, () -> service.preview("other", input));
        time.set(now.plusSeconds(600));
        assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        verifyNoInteractions(mysql, captures);
    }

    @Test
    void changedBindingDuringPreviewCancelsUnusableUnderlyingApproval() {
        when(mysql.preview(eq("workspace"), any())).thenAnswer(invocation -> {
            current.set(scope(candidate, "replacement", "parent", 1));
            return new MysqlInvestigationService.Preview("mysql-preview", "workspace", "loopback", "fixture", invocation.getArgument(1), now.plusSeconds(600).toString(), 16384, 10);
        });
        assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        verify(mysql).cancel("workspace", "mysql-preview");
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void explicitConsentAndExactDigestRequiredAndReplayDoesNotReadAgain() {
        var preview = service.preview("workspace", input);
        assertEquals(403, assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(),
                new ConversationOperationalReadService.Start("synthetic-request-1", preview.digest(), false))).status());
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(),
                new ConversationOperationalReadService.Start("synthetic-request-1", "0".repeat(64), true)));
        assertThrows(LibraryException.class, () -> service.execute("other", preview.id(), start(preview)));
        var result = service.execute("workspace", preview.id(), start(preview));
        assertEquals("SUCCEEDED", result.status());
        assertEquals("parent", result.parentBundleId());
        assertEquals(result, service.execute("workspace", preview.id(), start(preview)));
        assertEquals(result, service.get("workspace", preview.id()));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(),
                new ConversationOperationalReadService.Start("another-request-1", preview.digest(), true)));
        verify(mysql).execute(eq("workspace"), eq("mysql-preview"), eq("synthetic-request-1"), eq(true), any(Runnable.class));
    }

    @Test
    void sourceExcerptCandidateStillNeedsIndependentUserApproval() {
        var original = current.get();
        current.set(new PendingOperationalScope(original.id(), original.binding(), original.revision(), original.candidate(),
                PendingOperationalScope.Origin.SOURCE_EXCERPT, original.createdAt(), original.expiresAt()));
        var preview = service.preview("workspace", input);
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(),
                new ConversationOperationalReadService.Start("synthetic-request-1", preview.digest(), false)));
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void generationRevisionParentAndCandidateChangesInvalidateBeforeRead() {
        var original = current.get();
        var preview = service.preview("workspace", input);
        for (var changed : List.of(scope(candidate, "other-generation", "parent", 1),
                scope(candidate, "generation", "new-parent", 1), scope(candidate, "generation", "parent", 2),
                scope(new PendingOperationalScope.Mysql("receipts", List.of("id"), Map.of("id", "SYN-2"), 10), "generation", "parent", 1))) {
            current.set(changed);
            assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), start(preview)));
        }
        current.set(original);
        time.set(now.plusSeconds(601));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), start(preview)));
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void changedConnectionAfterReadSuppressesOutputAndCapture() {
        var preview = service.preview("workspace", input);
        when(mysql.execute(any(), any(), any(), anyBoolean(), any(Runnable.class))).thenAnswer(invocation -> {
            current.set(scope(candidate, "changed-generation", "parent", 1));
            return new MysqlInvestigationService.Result("mysql-preview", "SUCCEEDED", "synthetic output must not escape", 1, false, now.toString());
        });
        var result = service.execute("workspace", preview.id(), start(preview));
        assertEquals("DENIED", result.status());
        assertEquals("", result.output());
        assertThrows(LibraryException.class, () -> service.capturePreview("workspace", preview.id()));
        verifyNoInteractions(captures);
    }

    @Test
    void readerCheckpointRevalidatesAuthorityDuringExecution() {
        var preview = service.preview("workspace", input);
        when(mysql.execute(any(), any(), any(), anyBoolean(), any(Runnable.class))).thenAnswer(invocation -> {
            Runnable checkpoint = invocation.getArgument(4);
            checkpoint.run();
            current.set(scope(candidate, "generation-changed", "parent", 1));
            checkpoint.run();
            fail("Changed authority must stop the read checkpoint");
            return null;
        });
        var result = service.execute("workspace", preview.id(), start(preview));
        assertEquals("DENIED", result.status());
        assertEquals("", result.output());
    }

    @Test
    void cancellationSuppressesLateSuccessfulRead() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var preview = service.preview("workspace", input);
        when(mysql.execute(any(), any(), any(), anyBoolean(), any(Runnable.class))).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new MysqlInvestigationService.Result("mysql-preview", "SUCCEEDED", "late", 1, false, now.toString());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> service.execute("workspace", preview.id(), start(preview)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("CANCELLING", service.cancel("workspace", preview.id()).status());
            } finally { release.countDown(); }
            assertEquals("CANCELLED", future.get(5, TimeUnit.SECONDS).status());
            assertEquals("", service.get("workspace", preview.id()).output());
        }
        verify(mysql).cancel("workspace", "mysql-preview");
    }

    @Test
    void removedDatadogScopeCannotExecute() {
        current.set(scope(new PendingOperationalScope.Datadog("synthetic", "receipt-demo", Set.of("fixture"),
                now.minusSeconds(300), now, "Asia/Seoul", 5), "generation", "parent", 1));
        assertThrows(LibraryException.class, () -> service.preview("workspace", input));
        verifyNoInteractions(mysql, captures);
    }

    @Test
    void captureRequiresFreshSnapshotConsentAndReturnsSameChatBindingWithoutAi() {
        var preview = service.preview("workspace", input);
        assertThrows(LibraryException.class, () -> service.capturePreview("workspace", preview.id()));
        service.execute("workspace", preview.id(), start(preview));
        var snapshot = new OperationalCaptureService.Preview("capture", "a".repeat(64), "synthetic snapshot", 18, false, now.plusSeconds(300).toString(), true);
        when(captures.preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.MYSQL, "mysql-preview"))).thenReturn(snapshot);
        var summary = new QuestionAttachment.Summary("attachment", "fixture.txt", "text/plain", 18, 18, false);
        when(captures.capture(eq("workspace"), eq("capture"), any())).thenReturn(new OperationalCaptureService.Receipt(
                "capture", "CAPTURED", summary, "b".repeat(64), 0));
        var capturePreview = service.capturePreview("workspace", preview.id());
        assertNotEquals(snapshot.digest(), capturePreview.digest());
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(), start(preview)));
        var captureStart = new ConversationOperationalReadService.Start("capture-request-1234", capturePreview.digest(), true);
        var receipt = service.capture("workspace", preview.id(), captureStart);
        assertEquals("thread", receipt.threadId());
        assertEquals("parent", receipt.parentBundleId());
        assertEquals("attachment", receipt.attachment().id());
        assertEquals(0, receipt.externalCalls());
        verify(captures).capture("workspace", "capture", new OperationalCaptureService.Start("capture-request-1234", snapshot.digest(), true));
        verify(mysql, times(1)).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void failedEmptyAndCancelledResultsCannotBeCaptured() {
        var preview = service.preview("workspace", input);
        when(mysql.execute(any(), any(), any(), anyBoolean(), any(Runnable.class))).thenReturn(new MysqlInvestigationService.Result("mysql-preview", "EMPTY", "", 0, false, now.toString()));
        assertEquals("EMPTY", service.execute("workspace", preview.id(), start(preview)).status());
        assertThrows(LibraryException.class, () -> service.capturePreview("workspace", preview.id()));
        verifyNoInteractions(captures);
    }

    @Test
    void restartDoesNotRecreateOrReplayPreviousApproval() {
        var preview = service.preview("workspace", input);
        var restarted = new ConversationOperationalReadService(scopes, mysql, captures, attachments, clock);
        try {
            assertEquals(404, assertThrows(LibraryException.class, () -> restarted.execute("workspace", preview.id(), start(preview))).status());
            assertEquals(404, assertThrows(LibraryException.class, () -> restarted.captureReceipt("workspace", preview.id())).status());
        } finally { restarted.close(); }
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void committedCaptureIsRecoverableAfterPostCommitParentChangeAndScopeExpiry() {
        var preview = service.preview("workspace", input);
        service.execute("workspace", preview.id(), start(preview));
        var snapshot = new OperationalCaptureService.Preview("capture", "a".repeat(64), "synthetic", 9, false, now.plusSeconds(300).toString(), true);
        when(captures.preview(any(), any())).thenReturn(snapshot);
        byte[] content = "synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var attachment = new QuestionAttachment("attachment", "workspace", null, "fixture.txt", "text/plain", content,
                dev.knowledgeforge.search.domain.ContentDigest.of(content), "synthetic", false, now.toString());
        when(attachments.get("workspace", "attachment")).thenReturn(attachment);
        when(captures.capture(any(), any(), any())).thenAnswer(invocation -> {
            current.set(scope(candidate, "generation", "changed-parent", 1));
            return new OperationalCaptureService.Receipt("capture", "CAPTURED", attachment.summary(), attachment.contentHash(), 0);
        });
        var capture = service.capturePreview("workspace", preview.id());
        assertThrows(LibraryException.class, () -> service.capture("workspace", preview.id(),
                new ConversationOperationalReadService.Start("capture-request-1234", capture.digest(), true)));
        clearInvocations(scopes, mysql, captures);
        time.set(now.plusSeconds(1000));
        var receipt = service.captureReceipt("workspace", preview.id());
        assertEquals("CAPTURED", receipt.status());
        assertEquals("parent", receipt.parentBundleId());
        assertEquals(attachment.summary(), receipt.attachment());
        assertEquals(0, receipt.externalCalls());
        assertThrows(LibraryException.class, () -> service.captureReceipt("other", preview.id()));
        verifyNoInteractions(scopes, mysql, captures);
        when(attachments.get("workspace", "attachment")).thenThrow(new LibraryException(404, "removed"));
        assertThrows(LibraryException.class, () -> service.captureReceipt("workspace", preview.id()));
    }

    @Test
    void captureRecoveryDoesNotCreateMissingReceipt() {
        var preview = service.preview("workspace", input);
        assertEquals(404, assertThrows(LibraryException.class, () -> service.captureReceipt("workspace", preview.id())).status());
        verifyNoInteractions(captures, attachments);
        verify(mysql, never()).execute(any(), any(), any(), anyBoolean(), any(Runnable.class));
    }

    @Test
    void terminalCancellationCannotExposeOutputAfterParentChanged() {
        var preview = service.preview("workspace", input);
        service.execute("workspace", preview.id(), start(preview));
        current.set(scope(candidate, "generation", "new-parent", 1));
        assertThrows(LibraryException.class, () -> service.cancel("workspace", preview.id()));
    }

    @Test
    void blockedProviderPreviewDoesNotBlockUnrelatedStatusOrCancellation() throws Exception {
        var existing = service.preview("workspace", input);
        var blocked = scope(new PendingOperationalScope.Mysql("receipts", List.of("id"), Map.of("id", "SYN-2"), 10), "generation", "parent", 1);
        blocked = new PendingOperationalScope("blocked", blocked.binding(), blocked.revision(), blocked.candidate(),
                blocked.origin(), blocked.createdAt(), blocked.expiresAt());
        when(scopes.requireCurrent("workspace", "blocked")).thenReturn(blocked);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(mysql.preview(eq("workspace"), argThat(query -> query != null && "SYN-2".equals(query.equalities().get("id"))))).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new MysqlInvestigationService.Preview("blocked-preview", "workspace", "loopback", "fixture",
                    invocation.getArgument(1), now.plusSeconds(600).toString(), 16384, 10);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var preview = executor.submit(() -> service.preview("workspace", new ConversationOperationalReadService.Input("blocked", 1)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("PREVIEW", executor.submit(() -> service.get("workspace", existing.id())).get(1, TimeUnit.SECONDS).status());
                assertEquals("CANCELLED", executor.submit(() -> service.cancel("workspace", existing.id())).get(1, TimeUnit.SECONDS).status());
                assertThrows(LibraryException.class, () -> service.preview("workspace", new ConversationOperationalReadService.Input("blocked", 1)));
            } finally { release.countDown(); }
            assertNotNull(preview.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockedSnapshotDatabaseWorkDoesNotBlockUnrelatedStatusOrCancellation() throws Exception {
        var first = service.preview("workspace", input);
        service.execute("workspace", first.id(), start(first));
        var otherScope = current.get();
        otherScope = new PendingOperationalScope("other-scope", otherScope.binding(), otherScope.revision(), otherScope.candidate(),
                otherScope.origin(), otherScope.createdAt(), otherScope.expiresAt());
        when(scopes.requireCurrent("workspace", "other-scope")).thenReturn(otherScope);
        var other = service.preview("workspace", new ConversationOperationalReadService.Input("other-scope", 1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(captures.preview(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new OperationalCaptureService.Preview("snapshot", "a".repeat(64), "synthetic", 9, false, now.plusSeconds(300).toString(), true);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var snapshot = executor.submit(() -> service.capturePreview("workspace", first.id()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("PREVIEW", executor.submit(() -> service.get("workspace", other.id())).get(1, TimeUnit.SECONDS).status());
                assertEquals("CANCELLED", executor.submit(() -> service.cancel("workspace", other.id())).get(1, TimeUnit.SECONDS).status());
            } finally { release.countDown(); }
            assertNotNull(snapshot.get(5, TimeUnit.SECONDS));
        }
    }

    private PendingOperationalScope scope(PendingOperationalScope.Candidate value, String generation, String parent, long revision) {
        return new PendingOperationalScope("scope", new PendingOperationalScope.Binding("workspace", "thread", parent,
                value.tool(), "connection", generation), revision, value, PendingOperationalScope.Origin.MODEL_PROPOSAL, now, now.plusSeconds(600));
    }

    private ConversationOperationalReadService.Start start(ConversationOperationalReadService.Preview preview) {
        return new ConversationOperationalReadService.Start("synthetic-request-1", preview.digest(), true);
    }
}
