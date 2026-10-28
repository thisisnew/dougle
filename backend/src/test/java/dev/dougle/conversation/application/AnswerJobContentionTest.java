package dev.dougle.conversation.application;

import dev.dougle.conversation.domain.AnswerApproval;
import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.search.application.EvidenceAnswerPreview;
import dev.dougle.search.application.EvidenceBundleService;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class AnswerJobContentionTest {
    private final AnswerJobStore store = mock(AnswerJobStore.class);
    private final AnswerApprovalStore approvals = mock(AnswerApprovalStore.class);
    private final AtomicReference<AnswerJob> current = new AtomicReference<>();
    private final AtomicReference<Thread> providerThread = new AtomicReference<>();
    private final CountDownLatch providerEntered = new CountDownLatch(1);
    private final CountDownLatch emit = new CountDownLatch(1);
    private final CountDownLatch emitted = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch databaseEntered = new CountDownLatch(1);
    private final CountDownLatch databaseRelease = new CountDownLatch(1);
    private AnswerJobService service;

    @BeforeEach
    void prepare() {
        var library = mock(LibraryStore.class);
        var previews = mock(EvidenceAnswerPreview.class);
        var bundles = mock(EvidenceBundleService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "synthetic", "now")));
        var approval = new AnswerApproval("approval", "bundle", "CODEX", "fixture", "digest", "now",
                Instant.now().plusSeconds(60).toString(), null);
        when(approvals.find("bundle")).thenAnswer(call -> {
            blockDatabaseObserver();
            return Optional.of(approval);
        });
        when(previews.prepare(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new EvidenceAnswerPreview.Preview("bundle", "bundle-digest", "CODEX", "fixture", "digest", "synthetic", 9, 20000, null, null));
        when(store.find("workspace", "request-123456789")).thenAnswer(call -> {
            var snapshot = current.get();
            blockDatabaseObserver();
            return Optional.ofNullable(snapshot);
        });
        doAnswer(call -> { current.set(call.getArgument(0)); return null; }).when(store).create(any());
        when(store.update(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class))).thenAnswer(call -> {
            current.updateAndGet(job -> job.active() ? new AnswerJob(job.id(), job.workspaceId(), job.bundleId(), job.approvalId(),
                    job.provider(), job.requestedModel(), job.digest(), call.getArgument(2), call.getArgument(3), call.getArgument(4), "now", "now") : job);
            return true;
        });
        var model = mock(StreamingAnswerModel.class);
        when(model.generateWithText(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            providerThread.set(Thread.currentThread());
            providerEntered.countDown();
            try {
                emit.await();
                call.<java.util.function.Consumer<String>>getArgument(3).accept("synthetic partial");
                emitted.countDown();
                new CountDownLatch(1).await();
                return "unreachable";
            } catch (InterruptedException stopped) {
                interrupted.countDown();
                throw stopped;
            }
        });
        service = new AnswerJobService(store, approvals, previews, bundles, library, model, manager, mock(ConversationCompletion.class));
    }

    private void blockDatabaseObserver() throws InterruptedException {
        if (Thread.currentThread().getName().equals("slow-database-observer")) {
            databaseEntered.countDown();
            if (!databaseRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic database deadline");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowObserverDoesNotBlockCallback(boolean revalidation) throws Exception {
        exercise(revalidation, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowObserverDoesNotBlockCancellation(boolean revalidation) throws Exception {
        exercise(revalidation, true);
    }

    @Test
    void staleRevocationReadAfterCancellationCannotStopAnAlreadyFinishedWorker() throws Exception {
        var approved = approvals.find("bundle");
        when(approvals.find("bundle")).thenAnswer(call -> {
            blockDatabaseObserver();
            return Thread.currentThread().getName().equals("slow-database-observer") ? Optional.empty() : approved;
        });
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            service.submit("workspace", "bundle", new AnswerJobService.Request("request-123456789", "approval", "digest", true));
            assertThat(providerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var observer = tasks.submit(() -> {
                Thread.currentThread().setName("slow-database-observer");
                service.cancelInvalidatedRequest();
            });
            try {
                assertThat(databaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
                service.cancel("workspace", "request-123456789");
                providerThread.get().join(2000);
                assertThat(providerThread.get().isAlive()).isFalse();
                databaseRelease.countDown();
                observer.get(2, TimeUnit.SECONDS);
                assertThat(current.get().status()).isEqualTo("CANCELLED");
                verify(store, times(1)).update("workspace", "request-123456789", "CANCELLING", null, null);
            } finally {
                databaseRelease.countDown();
                service.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldObserverCannotCancelOrExposePartialFromReplacementJob(boolean revalidation) throws Exception {
        var approved = approvals.find("bundle");
        var nextApproval = new AnswerApproval("next-approval", "bundle", "CODEX", "fixture", "digest", "now",
                Instant.now().plusSeconds(60).toString(), null);
        var latestApproval = new AtomicReference<>(approved);
        when(approvals.find("bundle")).thenAnswer(call -> {
            blockDatabaseObserver();
            return Thread.currentThread().getName().equals("slow-database-observer") ? Optional.empty() : latestApproval.get();
        });
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            service.submit("workspace", "bundle", new AnswerJobService.Request("request-123456789", "approval", "digest", true));
            assertThat(providerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var observer = tasks.submit(() -> {
                Thread.currentThread().setName("slow-database-observer");
                if (revalidation) {
                    service.cancelInvalidatedRequest();
                    return "";
                }
                return service.partial("workspace", "request-123456789").text();
            });
            try {
                assertThat(databaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
                service.cancel("workspace", "request-123456789");
                providerThread.get().join(2000);
                assertThat(providerThread.get().isAlive()).isFalse();
                current.set(null);
                latestApproval.set(Optional.of(nextApproval));
                service.submit("workspace", "bundle", new AnswerJobService.Request("request-123456789", "next-approval", "digest", true));
                emit.countDown();
                assertThat(emitted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(service.partial("workspace", "request-123456789").text()).isEqualTo("synthetic partial");
                databaseRelease.countDown();
                assertThat(observer.get(2, TimeUnit.SECONDS)).isEmpty();
                assertThat(current.get().status()).isEqualTo("RUNNING");
                assertThat(current.get().approvalId()).isEqualTo("next-approval");
                assertThat(providerThread.get().isAlive()).isTrue();
                verify(store, times(1)).update("workspace", "request-123456789", "CANCELLING", null, null);
            } finally {
                databaseRelease.countDown();
                service.close();
            }
            assertThat(current.get().status()).isEqualTo("CANCELLED");
        }
    }

    private void exercise(boolean revalidation, boolean cancel) throws Exception {
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            service.submit("workspace", "bundle", new AnswerJobService.Request("request-123456789", "approval", "digest", true));
            assertThat(providerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            var observer = tasks.submit(() -> {
                Thread.currentThread().setName("slow-database-observer");
                if (revalidation) service.cancelInvalidatedRequest();
                else service.partial("workspace", "request-123456789");
            });
            try {
                assertThat(databaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
                if (cancel) {
                    var cancellation = tasks.submit(() -> service.cancel("workspace", "request-123456789"));
                    assertThat(interrupted.await(700, TimeUnit.MILLISECONDS)).as("provider interruption while observer DB remains blocked").isTrue();
                    cancellation.get(1, TimeUnit.SECONDS);
                } else {
                    emit.countDown();
                    assertThat(emitted.await(700, TimeUnit.MILLISECONDS)).as("callback while observer DB remains blocked").isTrue();
                }
            } finally {
                databaseRelease.countDown();
                observer.get(2, TimeUnit.SECONDS);
                service.close();
            }
            assertThat(current.get().status()).isEqualTo("CANCELLED");
            assertThat(service.partial("workspace", "request-123456789").text()).isEmpty();
        }
    }
}
