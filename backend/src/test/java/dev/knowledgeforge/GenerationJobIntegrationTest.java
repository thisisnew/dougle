package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.*;
import dev.knowledgeforge.knowledge.domain.GenerationJob;
import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GenerationJobIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-job-test-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired GenerationJobService jobs;
    @Autowired CuratorService curator;
    @Autowired KnowledgeService knowledge;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean CuratorModel model;
    String workspace;
    Document source;
    CountDownLatch entered;
    CountDownLatch release;
    AtomicReference<Thread> worker;
    static final String VALID = "{\"title\":\"관측\",\"claims\":[{\"text\":\"온도는 21도다.\",\"evidenceIds\":[\"E1\"]}]}";

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("가상 작업").id();
        String entry = library.upload(workspace, null, "synthetic.txt", "온도 21도".getBytes(StandardCharsets.UTF_8)).id();
        source = knowledge.create(workspace, "수동", "보존할 수동 문서", List.of(new Citation(entry, 1, 1)));
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        worker = new AtomicReference<>();
        when(model.generate(any(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return VALID;
        });
    }

    @AfterEach
    void finishWorker() throws Exception {
        if (library.session().workspaces().stream().anyMatch(item -> item.id().equals(workspace))) {
            for (var job : jobs.list(workspace)) if (job.active()) jobs.cancel(workspace, job.id());
        }
        release.countDown();
        if (worker.get() != null) { worker.get().join(5000); assertThat(worker.get().isAlive()).isFalse(); }
    }

    GenerationJobService.Request request() {
        var preview = curator.preview(workspace, source.card().id(), "CODEX");
        return new GenerationJobService.Request(UUID.randomUUID().toString(), "CODEX", 1, preview.digest(), true);
    }

    GenerationJob submit(GenerationJobService.Request request) { return jobs.submit(workspace, source.card().id(), request); }

    GenerationJob terminal(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            GenerationJob job = jobs.get(workspace, id);
            if (!job.active()) return job;
            Thread.sleep(10);
        }
        throw new AssertionError("Job did not finish");
    }

    @Test
    void persistsQueuedJobAndDeduplicatesRunningAndCompletedRequests() throws Exception {
        var request = request();
        GenerationJob job = submit(request);
        assertThat(job.status()).isEqualTo("QUEUED");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(submit(request).id()).isEqualTo(job.id());
        release.countDown();
        GenerationJob completed = terminal(job.id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.resultCardId()).isNotEqualTo(source.card().id());
        assertThat(knowledge.get(workspace, completed.resultCardId(), null).version().manuallyEdited()).isFalse();
        assertThat(knowledge.get(workspace, source.card().id(), null)).isEqualTo(source);
        knowledge.delete(workspace, source.card().id());
        assertThat(submit(request)).isEqualTo(completed);
        assertThat(jobs.cancel(workspace, job.id())).isEqualTo(completed);
        verify(model, times(1)).generate(any(), anyString());
    }

    @Test
    void rejectsUnapprovedMismatchedAndCrossWorkspaceRequests() throws Exception {
        var valid = request();
        assertThatThrownBy(() -> submit(new GenerationJobService.Request(valid.requestId(), "CODEX", 1, valid.digest(), false)))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(403));
        assertThatThrownBy(() -> submit(new GenerationJobService.Request(valid.requestId(), "CODEX", 1, "wrong", true))).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> submit(new GenerationJobService.Request("bad", "CODEX", 1, valid.digest(), true))).isInstanceOf(LibraryException.class);
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(model);
        GenerationJob job = submit(valid);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> submit(new GenerationJobService.Request(valid.requestId(), "CLAUDE", 1, valid.digest(), true)))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
        String other = library.createWorkspace("다른 공간").id();
        assertThat(jobs.list(other)).isEmpty();
        assertThatThrownBy(() -> jobs.get(other, job.id())).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> jobs.cancel(other, job.id())).isInstanceOf(LibraryException.class);
    }

    @Test
    void cancelInterruptsWorkerAndPreventsNewCard() throws Exception {
        GenerationJob job = submit(request());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.cancel(workspace, job.id()).status()).isEqualTo("CANCELLING");
        assertThat(terminal(job.id()).status()).isEqualTo("CANCELLED");
        assertThat(jobs.cancel(workspace, job.id()).status()).isEqualTo("CANCELLED");
        assertThat(knowledge.list(workspace)).hasSize(1);
    }

    @Test
    void lateModelOutputCannotPublishAfterCancellationAndSlotStaysOccupiedUntilExit() throws Exception {
        doAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try { waiting = !release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
            }
            return VALID;
        }).when(model).generate(any(), anyString());
        var request = request();
        GenerationJob job = submit(request);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        jobs.cancel(workspace, job.id());
        assertThat(jobs.get(workspace, job.id()).status()).isEqualTo("CANCELLING");
        assertThatThrownBy(() -> submit(request())).isInstanceOf(LibraryException.class);
        assertThat(submit(request).status()).isEqualTo("CANCELLING");
        release.countDown();
        assertThat(terminal(job.id()).status()).isEqualTo("CANCELLED");
        assertThat(knowledge.list(workspace)).hasSize(1);
    }

    @Test
    void failureIsRecordedWithoutRawExceptionAndRetryDoesNotCallAgain() throws Exception {
        doAnswer(invocation -> { worker.set(Thread.currentThread()); throw new IllegalStateException("secret-token"); })
                .when(model).generate(any(), anyString());
        var request = request();
        GenerationJob job = submit(request);
        GenerationJob failed = terminal(job.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).doesNotContain("secret-token");
        assertThat(submit(request)).isEqualTo(failed);
        assertThat(knowledge.list(workspace)).hasSize(1);
        verify(model, times(1)).generate(any(), anyString());
    }

    @Test
    void editDuringInferencePreservesHumanVersionAndFailsJob() throws Exception {
        GenerationJob job = submit(request());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        knowledge.revise(workspace, source.card().id(), 1, "편집", "사람이 수정", null);
        release.countDown();
        assertThat(terminal(job.id()).status()).isEqualTo("FAILED");
        assertThat(knowledge.list(workspace)).hasSize(1);
        assertThat(knowledge.get(workspace, source.card().id(), null).version().markdown()).isEqualTo("사람이 수정");
    }

    @Test
    void workspaceDeletionRemovesJobsAndInterruptsItsWorker() throws Exception {
        submit(request());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        library.deleteWorkspace(workspace);
        jobs.cancelDeletedWorkspace();
        worker.get().join(5000);
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card", Integer.class)).isZero();
    }

    @Test
    void successfulCardAndJobCommitAtomically() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_job_success BEFORE UPDATE OF status ON generation_job WHEN NEW.status='SUCCEEDED' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            GenerationJob job = submit(request());
            release.countDown();
            assertThat(terminal(job.id()).status()).isEqualTo("FAILED");
            assertThat(knowledge.list(workspace)).hasSize(1);
        } finally { jdbc.execute("DROP TRIGGER fail_job_success"); }
    }

    @Test
    void httpSubmissionIsAcceptedAndCancelRequiresMutationHeader() throws Exception {
        var request = request();
        String payload = "{\"requestId\":\"" + request.requestId() + "\",\"provider\":\"CODEX\",\"expectedVersion\":1,\"digest\":\"" + request.digest() + "\",\"approved\":true}";
        String base = "/api/workspaces/" + workspace;
        mvc.perform(post(base + "/knowledge/" + source.card().id() + "/generation/jobs")
                .header("X-Forge-Client", "1").contentType("application/json").content(payload))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(request.requestId()));
        mvc.perform(get(base + "/generation-jobs/" + request.requestId())).andExpect(status().isOk());
        mvc.perform(post(base + "/generation-jobs/" + request.requestId() + "/cancel")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/generation-jobs/" + request.requestId() + "/cancel").header("X-Forge-Client", "1"))
                .andExpect(status().isOk());
        assertThat(terminal(request.requestId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void interruptedJobReplayNeverResumesExternalTransmission() {
        var request = request();
        jdbc.update("""
                INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,created_at,updated_at)
                VALUES(?,?,?,'CODEX',1,?,'INTERRUPTED','old','now')
                """, request.requestId(), workspace, source.card().id(), request.digest());
        assertThat(submit(request).status()).isEqualTo("INTERRUPTED");
        verifyNoInteractions(model);
    }
}
