package dev.knowledgeforge;

import dev.knowledgeforge.conversation.application.*;
import dev.knowledgeforge.conversation.domain.*;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.search.application.*;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
class AnswerJobIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-answer-job-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired AnswerJobService jobs;
    @Autowired AnswerApprovalService approvals;
    @Autowired AnswerApprovalStore approvalStore;
    @Autowired EvidenceBundleStore bundleStore;
    @Autowired ConversationService conversations;
    @Autowired ConversationPreviewService contextPreviews;
    @Autowired ConversationRequestService contextRequests;
    @Autowired EvidenceAnswerPreview previews;
    @Autowired EvidenceBundleService bundles;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired dev.knowledgeforge.knowledge.application.KnowledgeService knowledge;
    @Autowired dev.knowledgeforge.knowledge.application.PublicationService publication;
    @MockitoBean AnswerModel model;
    String workspace;
    String entry;
    EvidenceBundle bundle;
    AnswerApproval approval;
    CountDownLatch entered;
    CountDownLatch release;
    AtomicReference<Thread> worker;
    static final String VALID = "{\"title\":\"관측\",\"claims\":[{\"text\":\"가상 관측 결과입니다.\",\"evidenceIds\":[\"R1\"]}]}";

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace"); search.refresh();
        workspace = library.createWorkspace("synthetic answer").id();
        entry = library.upload(workspace, null, "fixture.txt", "synthetic telescope".getBytes(java.nio.charset.StandardCharsets.UTF_8)).id();
        search.refresh(); bundle = bundles.create(workspace, "telescope", List.of(), false);
        var preview = previews.prepare(workspace, bundle.id(), "CODEX", "model-one");
        approval = approvals.approve(workspace, bundle.id(), "CODEX", "model-one", preview.digest(), true, null).approval();
        entered = new CountDownLatch(1); release = new CountDownLatch(1); worker = new AtomicReference<>();
        when(model.generate(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return VALID;
        });
    }

    @AfterEach
    void finish() throws Exception {
        release.countDown();
        if (worker.get() != null) { worker.get().join(5000); assertThat(worker.get().isAlive()).isFalse(); }
    }
    AnswerJobService.Request request() { return new AnswerJobService.Request(UUID.randomUUID().toString(), approval.id(), approval.digest(), true); }

    @Test
    void legacyCredentialApprovalCannotExecuteAndImmutableBundleIsPreserved() {
        var payload = bundle.payload();
        bundle = bundleStore.save(workspace, new EvidenceBundle.Payload("password=Synthet1c-Only-Value-7823",
                List.of(), false, "READY", payload.usedCharacters(), payload.maxCharacters(), payload.items(), List.of()));
        var original = bundle;
        approval = new AnswerApproval(UUID.randomUUID().toString(), bundle.id(), "CODEX", "model-one", "a".repeat(64),
                Instant.now().toString(), Instant.now().plusSeconds(600).toString(), null);
        approvalStore.save(approval);
        assertThatThrownBy(() -> submit(request())).isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
        assertThat(bundleStore.find(workspace, bundle.id()).orElseThrow()).isEqualTo(original);
        assertThat(approvalStore.find(bundle.id()).orElseThrow()).isEqualTo(approval);
        assertThat(jobs.list(workspace)).isEmpty();
        verifyNoInteractions(model);
    }
    AnswerJob submit(AnswerJobService.Request value) { return jobs.submit(workspace, bundle.id(), value); }
    AnswerJob terminal(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (jobs.get(workspace, id).active() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(jobs.get(workspace, id).active()).isFalse();
        if (worker.get() != null) worker.get().join(2000);
        return jobs.get(workspace, id);
    }
    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    ConversationRequest prepareContext() {
        jdbc.update("INSERT INTO answer_job VALUES('history',?,?,'history-approval','CODEX','model-one','old-digest','SUCCEEDED','Previous synthetic answer [R9]',NULL,'now','now')", workspace, bundle.id());
        var room = conversations.create(workspace, "synthetic context");
        conversations.append(workspace, room.id(), "history", 0);
        bundle = bundles.create(workspace, "telescope", List.of(), false);
        var selection = new ConversationPreviewService.Request(bundle.id(), 1, List.of("history"), "CODEX", "model-one");
        var preview = contextPreviews.prepare(workspace, room.id(), selection);
        var frozen = contextRequests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(selection, preview.digest()));
        approval = approvals.approve(workspace, bundle.id(), "CODEX", "model-one", preview.digest(), true, null).approval();
        return frozen;
    }

    @Test
    void approvedContextUsesFrozenPromptAndAtomicallyAppendsCompletedTurn() throws Exception {
        var frozen = prepareContext();
        var job = submit(request());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(terminal(job.id()).status()).isEqualTo("SUCCEEDED");
        verify(model).generate("CODEX", frozen.prompt(), "model-one");
        assertThat(conversations.get(workspace, frozen.conversationId()).turns()).extracting("jobId").containsExactly("history", job.id());
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("CONSUMED");
    }

    @Test
    void contextDeletionBeforeDispatchRejectsWithoutGeneration() {
        var frozen = prepareContext();
        conversations.delete(workspace, frozen.conversationId());
        fails(409, () -> submit(request()));
        verifyNoInteractions(model);
    }

    @Test
    void contextDeletionDuringGenerationDiscardsLateAnswer() throws Exception {
        var frozen = prepareContext();
        var job = submit(request());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        conversations.delete(workspace, frozen.conversationId());
        release.countDown();
        assertThat(terminal(job.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.get(workspace, job.id()).answer()).isNull();
    }

    @Test
    void approvalIsConsumedAtomicallyAndRetriesDoNotCallModelTwiceOrCreateKnowledge() throws Exception {
        var request = request(); var job = submit(request);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(submit(request).id()).isEqualTo(job.id());
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("CONSUMED");
        fails(409, () -> submit(request()));
        release.countDown();
        assertThat(terminal(job.id()).answer()).isEqualTo("가상 관측 결과입니다. [R1]");
        assertThat(submit(request).status()).isEqualTo("SUCCEEDED");
        fails(409, () -> submit(request()));
        verify(model, times(1)).generate(eq("CODEX"), contains("untrusted data"), eq("model-one"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card", Integer.class)).isZero();
    }

    @Test
    void rejectsNoConsentWrongDigestExpiredOrRevokedApprovalBeforeModelCall() {
        var request = request();
        fails(403, () -> submit(new AnswerJobService.Request(request.requestId(), approval.id(), approval.digest(), false)));
        fails(409, () -> submit(new AnswerJobService.Request(request.requestId(), approval.id(), "wrong", true)));
        jdbc.update("UPDATE answer_approval SET expires_at=?", Instant.now().minusSeconds(1).toString());
        fails(409, () -> submit(request));
        approvals.revoke(workspace, bundle.id(), approval.id());
        fails(409, () -> submit(request));
        assertThat(jobs.list(workspace)).isEmpty(); verifyNoInteractions(model);
    }

    @Test
    void changedEvidenceBlocksSubmissionAndDoesNotConsumeApproval() {
        library.deleteEntry(workspace, entry);
        fails(409, () -> submit(request()));
        assertThat(jobs.list(workspace)).isEmpty(); verifyNoInteractions(model);
    }

    @Test
    void cancellationInterruptsModelAndDiscardsLateOutput() throws Exception {
        doAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return VALID;
        }).when(model).generate(anyString(), anyString(), anyString());
        var request = request(); var job = submit(request);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        jobs.cancel(workspace, job.id());
        var result = terminal(job.id());
        assertThat(result.status()).isEqualTo("CANCELLED"); assertThat(result.answer()).isNull();
        assertThat(jobs.cancel(workspace, job.id())).isEqualTo(result);
        assertThat(submit(request).status()).isEqualTo("CANCELLED");
        fails(409, () -> submit(request()));
    }

    @Test
    void revocationDuringGenerationStopsWorkAndCannotStoreAnswer() throws Exception {
        var job = submit(request()); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        approvals.revoke(workspace, bundle.id(), approval.id()); jobs.cancelInvalidatedRequest();
        assertThat(terminal(job.id()).status()).isEqualTo("CANCELLED");
        assertThat(jobs.get(workspace, job.id()).answer()).isNull();
    }

    @Test
    void changingEvidenceDuringGenerationDiscardsAnswer() throws Exception {
        var job = submit(request()); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        library.deleteEntry(workspace, entry); release.countDown();
        assertThat(terminal(job.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.get(workspace, job.id()).answer()).isNull();
    }

    @Test
    void invalidCitationFailsWithoutSavingRawOutput() throws Exception {
        doReturn(VALID.replace("R1", "R99")).when(model).generate(anyString(), anyString(), anyString());
        var job = submit(request());
        assertThat(terminal(job.id()).status()).isEqualTo("FAILED");
        assertThat(jobs.get(workspace, job.id()).answer()).isNull();
        assertThat(jobs.get(workspace, job.id()).error()).doesNotContain("R99");
    }

    @Test
    void insufficientEvidenceBecomesExplicitAbstention() throws Exception {
        doReturn("{\"title\":\"do not show this\",\"claims\":[]}").when(model).generate(anyString(), anyString(), anyString());
        var job = submit(request());
        assertThat(terminal(job.id()).answer()).isEqualTo("제공된 근거로 답변할 수 없습니다.");
    }

    @Test
    void workspaceIsolationAndRequestIdConflict() throws Exception {
        var request = request(); var job = submit(request);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        String other = library.createWorkspace("other").id();
        fails(404, () -> jobs.get(other, job.id())); fails(404, () -> jobs.cancel(other, job.id()));
        fails(409, () -> submit(new AnswerJobService.Request(request.requestId(), approval.id(), "wrong", true)));
        release.countDown(); terminal(job.id());
    }

    @Test
    void deletingBundleRemovesJobAndInterruptsWorker() throws Exception {
        var job = submit(request()); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        bundles.delete(workspace, bundle.id()); jobs.cancelInvalidatedRequest();
        worker.get().join(5000); assertThat(worker.get().isAlive()).isFalse();
        fails(404, () -> jobs.get(workspace, job.id()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_job", Integer.class)).isZero();
    }

    @Test
    void httpSubmissionRequiresMutationHeaderAndReturnsAccepted() throws Exception {
        String path = "/api/workspaces/" + workspace + "/evidence-bundles/" + bundle.id() + "/answer-jobs";
        String body = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"approvalId\":\"" + approval.id() + "\",\"digest\":\"" + approval.digest() + "\",\"approved\":true}";
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json").content(body)).andExpect(status().isAccepted());
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown(); terminal(jobs.list(workspace).getFirst().id());
    }

    @Test
    void outputRejectsMalformedUncitedOversizedAndEmbeddedCitations() {
        for (String raw : List.of("not json", "{}", VALID.replace("[\"R1\"]", "[]"), VALID.replace("R1", "R1/E1"),
                VALID.replace("가상 관측 결과입니다.", "bad [R99]"), VALID.replace("가상 관측 결과입니다.", "x".repeat(2001)), "x".repeat(65537))) {
            fails(502, () -> AnswerOutput.validate(raw, bundle));
        }
        verifyNoInteractions(model);
    }

    @Test
    void publishedAnswerMustCiteUnderlyingSupportNotPublishedParent() {
        var card = knowledge.create(workspace, "telescope", "synthetic telescope [E1]",
                List.of(new dev.knowledgeforge.knowledge.domain.Knowledge.Citation(entry, 1, 1))).card();
        publication.review(workspace, card.id(), 1, "fixture reviewer", "checked", true, true);
        publication.publish(workspace, card.id(), 1, 0); search.refresh();
        var mixed = bundles.create(workspace, "telescope", List.of(), true);
        var published = mixed.payload().items().stream().filter(item -> item.kind().equals("KNOWLEDGE")).findFirst().orElseThrow();
        fails(502, () -> AnswerOutput.validate(VALID.replace("R1", published.label()), mixed));
        assertThat(AnswerOutput.validate(VALID.replace("R1", published.label() + "/E1"), mixed)).contains("[" + published.label() + "/E1]");
    }
}
