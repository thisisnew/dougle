package dev.dougle;

import dev.dougle.conversation.application.*;
import dev.dougle.library.application.LibraryService;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class AnswerPartialIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-answer-partial-"); }
        catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired AnswerJobService jobs;
    @Autowired AnswerApprovalService approvals;
    @Autowired EvidenceAnswerPreview previews;
    @Autowired EvidenceBundleService bundles;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @MockitoBean StreamingAnswerModel model;
    String workspace;
    String bundleId;
    String jobId;
    AnswerJobService.Request request;
    CountDownLatch entered;
    CountDownLatch release;
    AtomicReference<Thread> worker;
    AtomicReference<Consumer<String>> output;
    AtomicReference<String> result;

    @BeforeEach
    void prepare() {
        workspace = library.createWorkspace("stream " + UUID.randomUUID().toString().substring(0, 12)).id();
        library.upload(workspace, null, "fixture.txt", "synthetic telescope".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        search.refresh(); bundleId = bundles.create(workspace, "telescope", List.of(), false).id();
        var preview = previews.prepare(workspace, bundleId, "CLAUDE", "fixture");
        var approval = approvals.approve(workspace, bundleId, "CLAUDE", "fixture", preview.digest(), true, null).approval();
        jobId = UUID.randomUUID().toString(); request = new AnswerJobService.Request(jobId, approval.id(), preview.digest(), true);
        entered = new CountDownLatch(1); release = new CountDownLatch(1); worker = new AtomicReference<>(); output = new AtomicReference<>();
        result = new AtomicReference<>("{\"title\":\"fixture\",\"claims\":[{\"text\":\"최종 출력\",\"evidenceIds\":[\"R1\"]}]}");
        when(model.generateWithText(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            Consumer<String> consumer = invocation.getArgument(3); output.set(consumer);
            consumer.accept("미검증 가상 조각"); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return result.get();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        if (release != null) release.countDown();
        if (worker != null && worker.get() != null) worker.get().join(5000);
    }

    void start() throws Exception {
        jobs.submit(workspace, bundleId, request);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    void complete() throws Exception {
        release.countDown(); worker.get().join(5000);
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(jobs.partial(workspace, jobId).text()).isEmpty();
    }

    @Test
    void temporaryOutputIsScopedNotStoredAndReplacedOnlyByValidatedResult() throws Exception {
        start();
        assertThat(jobs.partial(workspace, jobId).text()).isEqualTo("미검증 가상 조각");
        assertThat(jobs.get(workspace, jobId).answer()).isNull();
        assertThatThrownBy(() -> jobs.partial("other", jobId)).isInstanceOf(LibraryException.class);
        jobs.submit(workspace, bundleId, request);
        complete();
        assertThat(jobs.get(workspace, jobId).answer()).isEqualTo("최종 출력 [R1]");
        output.get().accept("늦은 조각");
        assertThat(jobs.partial(workspace, jobId).text()).isEmpty();
        verify(model, times(1)).generateWithText(anyString(), anyString(), anyString(), any());
    }

    @Test
    void cancellationClearsTemporaryTextAndIgnoresLateCallbacks() throws Exception {
        start(); jobs.cancel(workspace, jobId); worker.get().join(5000);
        output.get().accept("늦은 조각");
        assertThat(jobs.partial(workspace, jobId).text()).isEmpty();
        assertThat(jobs.get(workspace, jobId).status()).isEqualTo("CANCELLED");
        assertThat(jobs.get(workspace, jobId).answer()).isNull();
    }

    @Test
    void invalidFinalCitationDiscardsTemporaryOutput() throws Exception {
        result.set(result.get().replace("R1", "R99")); start(); complete();
        assertThat(jobs.get(workspace, jobId).status()).isEqualTo("FAILED");
        assertThat(jobs.get(workspace, jobId).answer()).isNull();
    }

    @Test
    void deletionStopsAccessToPartialOutput() throws Exception {
        start(); bundles.delete(workspace, bundleId);
        assertThatThrownBy(() -> jobs.partial(workspace, jobId)).isInstanceOf(LibraryException.class);
    }
}
