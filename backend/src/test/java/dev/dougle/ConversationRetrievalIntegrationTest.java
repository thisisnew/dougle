package dev.dougle;

import dev.dougle.conversation.application.AnswerJobStore;
import dev.dougle.conversation.application.AnswerModel;
import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.investigation.application.PlanModel;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.ConversationRetrieval;
import dev.dougle.search.application.EvidenceBundleService;
import dev.dougle.search.application.EvidenceBundleStore;
import dev.dougle.search.application.SearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Timeout(15)
class ConversationRetrievalIntegrationTest {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-local-retrieval-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired ChatThreadService threads;
    @Autowired LibraryService library;
    @Autowired EvidenceBundleService bundles;
    @Autowired AnswerJobStore jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean SearchService search;
    @MockitoSpyBean ConversationRetrieval retrieval;
    @MockitoSpyBean EvidenceBundleStore bundleStore;
    @MockitoBean AnswerModel answers;
    @MockitoBean PlanModel plans;
    private String workspace;
    private String entry;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("local retrieval").id();
        entry = library.upload(workspace, null, "Strategy.txt", "Strategy chooses a hand. Player delegates to Strategy.".getBytes(StandardCharsets.UTF_8)).id();
        search.refresh();
    }

    private ChatThreadService.Create request(String id) { return new ChatThreadService.Create(id, null, null, "Strategy", List.of()); }

    @Test void ordinaryQuestionUsesTypedSearchWithoutDatabaseTransactionAndReplayDoesNotSearchAgain() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(Thread.currentThread().getName()).isEqualTo("conversation-local-search");
            return call.callRealMethod();
        }).when(search).searchQuestionCandidates(workspace, "Strategy");
        var first = threads.create(workspace, request("same"));
        assertThat(first.payload().items()).extracting("entryId").contains(entry);
        assertThat(threads.create(workspace, request("same"))).isEqualTo(first);
        verify(search, times(1)).searchQuestionCandidates(workspace, "Strategy");
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @ParameterizedTest
    @EnumSource(value = ConversationRetrieval.Status.class, names = {"DENIED", "TIMEOUT", "FAILED", "CANCELLED"})
    void unsuccessfulSearchNeverBecomesEmptyBundleOrAnswer(ConversationRetrieval.Status status) {
        doReturn(new ConversationRetrieval.Result(status, List.of())).when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> threads.create(workspace, request("failed")))
                .isInstanceOf(LibraryException.class).hasMessageContaining("LOCAL_SEARCH_" + status);
        assertThat(bundles.list(workspace)).isEmpty();
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void actualEmptySearchPreservesExplicitNoEvidenceWithoutModelDispatch() {
        assertThat(search.searchQuestionCandidates(workspace, "Strategy"))
                .extracting(dev.dougle.search.application.SearchIndex.Hit::entryId).contains(entry);
        library.deleteEntry(workspace, entry);
        search.refresh();
        assertThat(search.searchQuestionCandidates(workspace, "Strategy")).isEmpty();
        var result = threads.create(workspace, request("empty"));
        assertThat(result.payload().status()).isEqualTo("NO_EVIDENCE");
        assertThat(result.payload().items()).isEmpty();
        assertThat(bundles.get(workspace, result.id()).payload().status()).isEqualTo("NO_EVIDENCE");
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void cancelledAfterRetrievalCannotCommitEvenIfPortReturnsOk() {
        doAnswer(call -> {
            var result = call.callRealMethod();
            call.<ConversationRetrieval.Attempt>getArgument(2).cancel();
            return result;
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> threads.create(workspace, request("cancelled"))).hasMessageContaining("LOCAL_SEARCH_CANCELLED");
        assertThat(bundles.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void cancellationDuringCaptureRollsBackSavedBundleAndReleasesRequestKey() {
        var control = new java.util.concurrent.atomic.AtomicReference<ConversationRetrieval.Attempt>();
        doAnswer(call -> {
            control.set(call.getArgument(2));
            return call.callRealMethod();
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        doAnswer(call -> {
            var saved = call.callRealMethod();
            control.get().cancel();
            return saved;
        }).when(bundleStore).save(eq(workspace), any());
        assertThatThrownBy(() -> threads.create(workspace, request("rollback"))).hasMessageContaining("LOCAL_SEARCH_CANCELLED");
        assertThat(bundles.list(workspace)).isEmpty();
        doCallRealMethod().when(bundleStore).save(eq(workspace), any());
        assertThat(threads.create(workspace, request("rollback")).payload().status()).isEqualTo("READY");
    }

    @Test void changedSourceBetweenRetrievalAndCaptureFailsClosed() {
        doAnswer(call -> {
            var result = call.callRealMethod();
            jdbc.update("DELETE FROM entry WHERE id=?", entry);
            return result;
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> threads.create(workspace, request("removed"))).isInstanceOf(LibraryException.class);
        assertThat(bundles.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void workspaceDeletedAfterSearchCannotPublishBundle() {
        doAnswer(call -> {
            var result = call.callRealMethod();
            jdbc.update("DELETE FROM workspace WHERE id=?", workspace);
            return result;
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> threads.create(workspace, request("removed-workspace")))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_bundle", Integer.class)).isZero();
        verifyNoInteractions(answers, plans);
    }

    @Test void foreignWorkspaceHitFailsClosedWithoutPersistingItsContent() {
        String other = library.createWorkspace("foreign fixture").id();
        library.upload(other, null, "Foreign.txt", "Strategy private foreign fixture".getBytes(StandardCharsets.UTF_8));
        search.refresh();
        var foreign = search.searchQuestionCandidates(other, "Strategy");
        assertThat(foreign).isNotEmpty();
        doReturn(new ConversationRetrieval.Result(ConversationRetrieval.Status.OK, foreign))
                .when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> threads.create(workspace, request("foreign")))
                .isInstanceOf(LibraryException.class).hasMessageContaining("범위").hasMessageNotContaining("private foreign");
        assertThat(bundles.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void staleAndDeletedCandidatesBeyondSelectedFiveRemainExcludedRatherThanFailingQuestion() {
        for (int index = 0; index < 6; index++) {
            library.upload(workspace, null, "Candidate-" + index + ".txt", ("Strategy candidate " + index).getBytes(StandardCharsets.UTF_8));
        }
        search.refresh();
        var hits = new java.util.ArrayList<>(search.searchQuestionCandidates(workspace, "Strategy"));
        assertThat(hits).hasSize(7);
        var stale = hits.get(5);
        hits.set(5, new dev.dougle.search.application.SearchIndex.Hit(stale.entryId(), stale.name(), stale.sourceId(), stale.domain(),
                stale.path(), stale.snippet(), stale.score(), stale.startLine(), stale.endLine(), stale.bodyMatch(), stale.kind(),
                stale.cardId(), stale.version(), stale.domains(), stale.freshness(), "changed-hash"));
        var deleted = hits.get(6);
        doAnswer(call -> {
            jdbc.update("DELETE FROM entry WHERE id=?", deleted.entryId());
            return new ConversationRetrieval.Result(ConversationRetrieval.Status.OK, hits);
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        var captured = threads.create(workspace, request("excluded"));
        assertThat(captured.payload().items()).hasSize(5);
        assertThat(captured.payload().excluded()).anySatisfy(value -> {
            assertThat(value.entryId()).isEqualTo(stale.entryId());
            assertThat(value.reason()).isEqualTo("ITEM_LIMIT");
        }).anySatisfy(value -> {
            assertThat(value.entryId()).isEqualTo(deleted.entryId());
            assertThat(value.reason()).isEqualTo("REMOVED");
        });
        verifyNoInteractions(answers, plans);
    }

    @Test @Timeout(25)
    void deadlineExpiryBeforeOuterTransactionCommitRollsBackAlreadyCapturedBundle() {
        var control = new java.util.concurrent.atomic.AtomicReference<ConversationRetrieval.Attempt>();
        doAnswer(call -> {
            control.set(call.getArgument(2));
            return call.callRealMethod();
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            threads.create(workspace, request("outer-expired"));
            while (control.get().remaining() > 0) java.util.concurrent.locks.LockSupport.parkNanos(control.get().remaining());
        })).isInstanceOf(LibraryException.class).hasMessageContaining("LOCAL_SEARCH_TIMEOUT");
        assertThat(bundles.list(workspace)).isEmpty();
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }

    @Test void timedOutUncooperativeSearchNeverCommitsWhenItEventuallyReturns() throws Exception {
        var lateHits = search.searchQuestionCandidates(workspace, "Strategy");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (release.getCount() > 0 && System.nanoTime() < deadline) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { }
            }
            finished.countDown();
            return lateHits;
        }).when(search).searchQuestionCandidates(workspace, "Strategy");
        try (var bounded = new dev.dougle.investigation.adapter.BoundedConversationRetrieval(search)) {
            doAnswer(call -> bounded.retrieve(call.getArgument(0), call.getArgument(1),
                    new ConversationRetrieval.Attempt(Duration.ofMillis(300))))
                    .when(retrieval).retrieve(eq(workspace), anyList(), any());
            try {
                assertThatThrownBy(() -> threads.create(workspace, request("timeout"))).hasMessageContaining("LOCAL_SEARCH_TIMEOUT");
                assertThat(entered.getCount()).isZero();
                assertThat(bundles.list(workspace)).isEmpty();
                release.countDown();
                assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(bundles.list(workspace)).isEmpty();
                assertThat(jobs.list(workspace)).isEmpty();
                verifyNoInteractions(answers, plans);
            } finally { release.countDown(); }
        }
    }

    @Test void parentChangedWhileSearchRunsCannotBranchSilently() throws Exception {
        var root = threads.create(workspace, request("root"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("delayed-chat-request")) {
                entered.countDown();
                assertThat(release.await(4, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(retrieval).retrieve(eq(workspace), anyList(), any());
        var first = new ChatThreadService.Create("delayed", root.payload().chatContext().threadId(), root.id(), "Strategy", List.of());
        var next = new ChatThreadService.Create("winner", first.threadId(), root.id(), "Strategy", List.of());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var pending = tasks.submit(() -> {
                    Thread.currentThread().setName("delayed-chat-request");
                    return catchThrowable(() -> threads.create(workspace, first));
                });
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> threads.create(workspace, first)).isInstanceOf(LibraryException.class).hasMessageContaining("진행 중");
                var winner = threads.create(workspace, next);
                release.countDown();
                assertThat(pending.get(3, TimeUnit.SECONDS)).isInstanceOf(LibraryException.class);
                assertThat(threads.get(workspace, first.threadId()).bundles()).containsExactly(root, winner);
            } finally { release.countDown(); }
        }
        verifyNoInteractions(answers, plans);
    }

    @Test void legacyCallerTransactionDoesNotDeadlockSingleConnectionPoolAndRollbackStillOwnsCapture() {
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM workspace", Integer.class);
            assertThat(threads.create(workspace, request("outer")).payload().status()).isEqualTo("READY");
            status.setRollbackOnly();
        });
        assertThat(bundles.list(workspace)).isEmpty();
        verifyNoInteractions(answers, plans);
    }
}
