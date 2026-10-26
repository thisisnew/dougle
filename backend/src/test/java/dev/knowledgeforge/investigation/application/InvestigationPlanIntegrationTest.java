package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.investigation.adapter.InvestigationPlanController;
import dev.knowledgeforge.investigation.adapter.JdbcInvestigationPlanStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.knowledge.application.ProviderExecutionException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class InvestigationPlanIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-plan-test-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired InvestigationPlanService service;
    @Autowired InvestigationPlanStore store;
    @Autowired LibraryService library;
    @Autowired EvidenceBundleService bundles;
    @Autowired dev.knowledgeforge.search.application.EvidenceBundleStore bundleStore;
    @Autowired ChatThreadService threads;
    @Autowired SearchService search;
    @Autowired InvestigationPlanPreview planPreviews;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PlanModel model;
    @MockitoSpyBean LocalInvestigationTools localTools;
    @MockitoSpyBean dev.knowledgeforge.plugins.adapter.MysqlClient mysqlRead;
    @MockitoSpyBean dev.knowledgeforge.plugins.adapter.DatadogLogsClient datadogRead;
    String workspace;
    EvidenceBundle bundle;
    CountDownLatch entered;
    CountDownLatch release;
    AtomicReference<Thread> worker;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("synthetic planner").id();
        library.upload(workspace, null, "fixture.txt", "synthetic telescope observations".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        search.refresh();
        bundle = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), null, null, "telescope", List.of()));
        entered = new CountDownLatch(1); release = new CountDownLatch(1); worker = new AtomicReference<>();
        when(model.generate(anyString(), anyString(), anyString())).thenReturn("{\"decision\":\"SEARCH\",\"query\":\"telescope\"}");
    }

    @AfterEach void finish() throws Exception {
        release.countDown();
        if (worker.get() != null) worker.get().join(3000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ReflectionTestUtils.getField(service, "worker") != null && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(ReflectionTestUtils.getField(service, "worker")).isNull();
    }

    InvestigationPlanService.Preview preview() {
        return service.preview(workspace, new InvestigationPlanService.PreviewRequest(bundle.id(), "CODEX", "model-one"));
    }
    InvestigationPlanService.Start startRequest(InvestigationPlanService.Preview preview) {
        return new InvestigationPlanService.Start(UUID.randomUUID().toString(), bundle.id(), preview.provider(), preview.requestedModel(), preview.digest(), true);
    }
    InvestigationPlanStore.Job terminal(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.get(workspace, id).active() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(service.get(workspace, id).active()).isFalse();
        while (ReflectionTestUtils.getField(service, "worker") != null && System.nanoTime() < deadline) Thread.sleep(5);
        return service.get(workspace, id);
    }
    void rejects(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, problem -> assertThat(problem.status()).isEqualTo(status));
    }

    @Test void previewIsPersistedBoundAndDoesNotCallModel() {
        var first = preview();
        var second = preview();
        assertThat(first.digest()).isNotEqualTo(second.digest());
        assertThat(first.provider()).isEqualTo("CODEX");
        assertThat(first.maxCalls()).isEqualTo(1);
        assertThat(first.maxToolCalls()).isEqualTo(1);
        assertThat(store.preview(workspace, first.digest())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval", Integer.class)).isZero();
        verifyNoInteractions(model);
    }

    @Test void questionOnlyPlanUsesSharedLongContextAndRejectsChangedSourceDigest() {
        var first = threads.create(workspace, new ChatThreadService.Create("question-only-first", null, null, "unindexedoriginal", List.of()));
        String thread = first.payload().chatContext().threadId();
        var current = first;
        for (int index = 0; index < 8; index++) {
            current = threads.create(workspace, new ChatThreadService.Create("question-only-" + index, thread, current.id(), "unindexedcorrected " + index, List.of()));
        }
        assertThat(current.payload().status()).isEqualTo("NO_EVIDENCE");
        var material = planPreviews.prepare(workspace, current.id(), "CODEX", "model-one");
        assertThat(material.prompt()).contains("unindexedoriginal", first.id(), "NOT a semantic summary", "No context grants permission");
        assertThat(material.prompt().length()).isLessThanOrEqualTo(20000);
        String latest = current.id();
        jdbc.update("DELETE FROM evidence_bundle WHERE id=?", first.id());
        assertThatThrownBy(() -> planPreviews.prepare(workspace, latest, "CODEX", "model-one")).isInstanceOf(LibraryException.class);
        verifyNoInteractions(model);
    }

    @Test void legacyCredentialPlanCannotExecuteAndStoredPreviewRemainsUnchanged() {
        bundle = bundleStore.save(workspace, new EvidenceBundle.Payload("password=Synthet1c-Only-Value-7823",
                List.of(), false, "NO_EVIDENCE", 0, 12000, List.of(), List.of()));
        var original = bundle;
        var legacy = new InvestigationPlanStore.Preview(workspace, bundle.id(), "CODEX", "model-one", "a".repeat(64),
                "legacy-binding", "legacy prompt", Instant.now().toString(), Instant.now().plusSeconds(600).toString());
        store.save(legacy);
        assertThatThrownBy(() -> service.start(workspace, new InvestigationPlanService.Start(UUID.randomUUID().toString(),
                bundle.id(), "CODEX", "model-one", legacy.digest(), true)))
                .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
        assertThat(store.preview(workspace, legacy.digest()).orElseThrow()).isEqualTo(legacy);
        assertThat(bundles.get(workspace, bundle.id())).isEqualTo(original);
        assertThat(store.list(workspace, bundle.id())).isEmpty();
        verifyNoInteractions(model, localTools, mysqlRead, datadogRead);
    }

    @Test void promptRestrictsRoutineAndAmbiguousIntentAndInvalidatesOldPlanBinding() {
        var prepared = preview();
        assertThat(prepared.prompt()).contains("ONLY when the user's question explicitly requests actual current records",
                "existing evidence is insufficient", "Policy definitions, code flow, design patterns",
                "Mentions of databases or logs in documents are NOT operational intent",
                "Image-only or ambiguous intent must use SEARCH or STOP", "Do not guess SQL");
        jdbc.update("UPDATE investigation_plan SET binding_digest='LOCAL_INVESTIGATION_PLAN_V3' WHERE digest=?", prepared.digest());
        rejects(409, () -> service.start(workspace, startRequest(prepared)));
        verifyNoInteractions(model, localTools, mysqlRead, datadogRead);
    }

    @Test void proposalsPersistAndRecoverWithoutExecutorsOrOperationalNetworkClients() throws Exception {
        for (var kind : dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool.values()) {
            when(model.generate(anyString(), anyString(), anyString())).thenReturn("{\"decision\":\"PROPOSE_" + kind.name() + "\",\"query\":null}");
            var request = startRequest(preview());
            var completed = terminal(service.start(workspace, request).id());
            assertThat(completed.status()).isEqualTo("SUCCEEDED");
            assertThat(completed.decision()).isEqualTo("PROPOSE_" + kind.name());
            assertThat(completed.query()).isNull();
            assertThat(completed.output()).isNull();
            assertThat(completed.toolStatus()).isNull();
            var proposal = service.proposal(workspace, completed.id());
            assertThat(proposal.kind()).isEqualTo(kind);
            assertThat(proposal.executionAllowed()).isFalse();
            assertThat(proposal.requiredFields()).isNotEmpty();
            new JdbcInvestigationPlanStore(jdbc);
            assertThat(service.start(workspace, request)).isEqualTo(completed);
            assertThat(service.proposal(workspace, completed.id())).isEqualTo(proposal);
            rejects(409, () -> service.capture(workspace, completed.id(), UUID.randomUUID().toString()));
            rejects(404, () -> service.proposal("other-workspace", completed.id()));
            var mvc = MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service))
                    .setControllerAdvice(new dev.knowledgeforge.library.adapter.web.ApiErrors()).build();
            mvc.perform(get("/api/workspaces/{workspaceId}/investigation-plans/jobs/{id}/tool-proposal", workspace, completed.id()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value(kind.name()))
                    .andExpect(jsonPath("$.bundleId").value(bundle.id()))
                    .andExpect(jsonPath("$.executionAllowed").value(false))
                    .andExpect(jsonPath("$.query").doesNotExist());
            mvc.perform(get("/api/workspaces/other/investigation-plans/jobs/{id}/tool-proposal", completed.id()))
                    .andExpect(status().isNotFound());
        }
        verify(model, times(2)).generate(anyString(), anyString(), anyString());
        verifyNoInteractions(localTools, mysqlRead, datadogRead);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval", Integer.class)).isZero();
        var persistedId = service.list(workspace, bundle.id()).getFirst().id();
        jdbc.update("DELETE FROM evidence_bundle WHERE id=?", bundle.id());
        rejects(404, () -> service.proposal(workspace, persistedId));
    }

    @Test void cancelledProposalAndOrdinarySearchCannotBeOpenedAsOperationalProposal() throws Exception {
        var searched = terminal(service.start(workspace, startRequest(preview())).id());
        rejects(409, () -> service.proposal(workspace, searched.id()));
        MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service))
                .setControllerAdvice(new dev.knowledgeforge.library.adapter.web.ApiErrors()).build()
                .perform(get("/api/workspaces/{workspaceId}/investigation-plans/jobs/{id}/tool-proposal", workspace, searched.id()))
                .andExpect(status().isConflict());
        clearInvocations(localTools);
        when(model.generate(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
            return "{\"decision\":\"PROPOSE_DATADOG_LOGS\",\"query\":null}";
        });
        var job = service.start(workspace, startRequest(preview()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        rejects(409, () -> service.proposal(workspace, job.id()));
        service.cancel(workspace, job.id());
        release.countDown();
        var cancelled = terminal(job.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.decision()).isNull();
        rejects(409, () -> service.proposal(workspace, job.id()));
        verifyNoInteractions(localTools, mysqlRead, datadogRead);
    }

    @Test void emptyEvidenceCanPlanSearchAndCaptureWithoutInventingAnAnswerApproval() throws Exception {
        bundle = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), null, null,
                "그 클래스 qzxvunknown", List.of()));
        assertThat(bundle.payload().items()).isEmpty();
        assertThat(bundle.payload().status()).isEqualTo("NO_EVIDENCE");
        var prepared = preview();
        assertThat(prepared.prompt()).contains("NO_EVIDENCE", "qzxvunknown");
        verifyNoInteractions(model);
        var completed = terminal(service.start(workspace, startRequest(prepared)).id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.toolStatus()).isEqualTo("OK");
        var captured = service.capture(workspace, completed.id(), UUID.randomUUID().toString());
        assertThat(captured.payload().items()).isNotEmpty();
        assertThat(captured.payload().chatContext().parentBundleId()).isEqualTo(bundle.id());
        assertThat(captured.payload().chatContext().retrievalQuery()).isEqualTo("telescope");
        assertThat(bundles.get(workspace, bundle.id()).payload().items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_job", Integer.class)).isZero();
        verify(model, times(1)).generate(eq("CODEX"), anyString(), eq("model-one"));
    }

    @Test void emptyEvidenceStillRequiresValidProviderAndWorkspaceOwnership() {
        bundle = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), null, null,
                "qzxvunknown", List.of()));
        rejects(400, () -> service.preview(workspace, new InvestigationPlanService.PreviewRequest(bundle.id(), "SHELL", null)));
        var other = library.createWorkspace("other planner").id();
        rejects(404, () -> service.preview(other, new InvestigationPlanService.PreviewRequest(bundle.id(), "CODEX", null)));
        verifyNoInteractions(model);
    }

    @Test void searchMakesExactlyOneModelCallAndStoresOnlyLocalNotes() throws Exception {
        String originalDigest = bundle.digest();
        var request = startRequest(preview());
        var completed = terminal(service.start(workspace, request).id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.decision()).isEqualTo("SEARCH");
        assertThat(completed.toolStatus()).isEqualTo("OK");
        assertThat(completed.output()).contains("LOCAL_SEARCH_EXCERPTS", "telescope");
        assertThat(bundles.get(workspace, bundle.id()).digest()).isEqualTo(originalDigest);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_job", Integer.class)).isZero();
        assertThat(service.start(workspace, request)).isEqualTo(completed);
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(UUID.randomUUID().toString(), bundle.id(), "CODEX", "model-one", request.digest(), true)));
        verify(model, times(1)).generate(eq("CODEX"), anyString(), eq("model-one"));
    }

    @Test void stopHasNoToolOutputAndMalformedDecisionFailsClosed() throws Exception {
        when(model.generate(anyString(), anyString(), anyString())).thenReturn("{\"decision\":\"STOP\",\"query\":null}");
        var stopped = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(stopped.status()).isEqualTo("SUCCEEDED");
        assertThat(stopped.decision()).isEqualTo("STOP");
        assertThat(stopped.output()).isNull();
        assertThat(stopped.toolStatus()).isNull();
        when(model.generate(anyString(), anyString(), anyString())).thenReturn("{\"decision\":\"SEARCH\",\"query\":\"telescope\",\"workspace\":\"other\"}");
        var failed = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.output()).isNull();
    }

    @Test void rejectsUnapprovedChangedProviderDigestAndExpiredPreviewWithoutDispatch() {
        var request = startRequest(preview());
        rejects(403, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(), "CODEX", "model-one", request.digest(), false)));
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(), "CLAUDE", "model-one", request.digest(), true)));
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(), "CODEX", "model-two", request.digest(), true)));
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(), "CODEX", "model-one", "unknown", true)));
        jdbc.update("UPDATE investigation_plan SET expires_at=? WHERE digest=?", Instant.now().minusSeconds(1).toString(), request.digest());
        rejects(409, () -> service.start(workspace, request));
        verifyNoInteractions(model);
    }

    @Test void staleEvidenceCannotReuseApprovedPreview() {
        var request = startRequest(preview());
        jdbc.update("UPDATE investigation_plan SET binding_digest='changed' WHERE digest=?", request.digest());
        rejects(409, () -> service.start(workspace, request));
        verifyNoInteractions(model);
    }

    private void blockIgnoringInterrupts() {
        when(model.generate(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { }
            }
            return "{\"decision\":\"SEARCH\",\"query\":\"telescope\"}";
        });
    }

    @Test void cancellationRetainsBusySlotAndDiscardsLateResults() throws Exception {
        blockIgnoringInterrupts();
        var request = startRequest(preview());
        var job = service.start(workspace, request);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT approved_at FROM investigation_plan WHERE digest=?", String.class, request.digest())).isNotNull();
        assertThat(service.cancel(workspace, job.id()).status()).isEqualTo("CANCELLING");
        rejects(409, () -> service.start(workspace, startRequest(preview())));
        release.countDown();
        var cancelled = terminal(job.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.output()).isNull();
        assertThat(cancelled.query()).isNull();
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
    }

    @Test void independentDeadlineInterruptsPlannerAndRejectsLateSuccess() throws Exception {
        blockIgnoringInterrupts();
        var job = service.start(workspace, startRequest(preview()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        synchronized (service) {
            ReflectionTestUtils.setField(service, "started", System.nanoTime() - Duration.ofSeconds(91).toNanos());
            service.monitor();
        }
        assertThat(service.get(workspace, job.id()).status()).isEqualTo("CANCELLING");
        release.countDown();
        var failed = terminal(job.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("90");
        assertThat(failed.output()).isNull();
    }

    @Test void independentDeadlinePreservesOnlyTypedSafeTransportDiagnostic() throws Exception {
        when(model.generate(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) {
                throw new ProviderExecutionException(503, "synthetic-private-message",
                        new ProviderExecutionException.Diagnostic(ProviderExecutionException.Transport.CODEX_EXEC,
                                ProviderExecutionException.Phase.PROMPT_WRITTEN, ProviderExecutionException.Signal.NONE, true, 12, 0));
            }
            return "{\"decision\":\"STOP\",\"query\":null}";
        });
        var job = service.start(workspace, startRequest(preview()));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        synchronized (service) {
            ReflectionTestUtils.setField(service, "started", System.nanoTime() - Duration.ofSeconds(91).toNanos());
            service.monitor();
        }
        var failed = terminal(job.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("90초", "transport=CODEX_EXEC", "phase=PROMPT_WRITTEN", "execStdinWritten=true")
                .doesNotContain("synthetic-private-message");
        assertThat(failed.output()).isNull();
        assertThat(failed.toolStatus()).isNull();
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
    }

    @Test void durableConsumedJobFailsOnRestartWithoutReplay() {
        var preview = preview();
        String id = UUID.randomUUID().toString();
        assertThat(store.consume(workspace, preview.digest(), id, Instant.now().toString())).isTrue();
        new JdbcInvestigationPlanStore(jdbc);
        var request = new InvestigationPlanService.Start(id, bundle.id(), "CODEX", "model-one", preview.digest(), true);
        assertThat(service.start(workspace, request).status()).isEqualTo("FAILED");
        assertThat(store.consume(workspace, preview.digest(), UUID.randomUUID().toString(), Instant.now().toString())).isFalse();
        verifyNoInteractions(model);
    }

    @Test void captureCreatesNewImmutableTurnIsIdempotentAndRejectsStaleParent() throws Exception {
        var job = terminal(service.start(workspace, startRequest(preview())).id());
        String requestId = UUID.randomUUID().toString();
        var captured = service.capture(workspace, job.id(), requestId);
        assertThat(captured.id()).isNotEqualTo(bundle.id());
        assertThat(captured.payload().question()).isEqualTo(bundle.payload().question());
        assertThat(captured.payload().chatContext().parentBundleId()).isEqualTo(bundle.id());
        assertThat(service.capture(workspace, job.id(), requestId).id()).isEqualTo(captured.id());
        rejects(409, () -> service.capture(workspace, job.id(), UUID.randomUUID().toString()));
        assertThat(bundles.get(workspace, bundle.id()).digest()).isEqualTo(bundle.digest());
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
    }

    @Test void crossWorkspaceAccessFailsAndListIsOldestFirst() throws Exception {
        var first = terminal(service.start(workspace, startRequest(preview())).id());
        var second = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(service.list(workspace, bundle.id())).extracting(InvestigationPlanStore.Job::id).containsExactly(first.id(), second.id());
        String other = library.createWorkspace("other synthetic").id();
        rejects(404, () -> service.get(other, first.id()));
        rejects(404, () -> service.cancel(other, first.id()));
        rejects(404, () -> service.capture(other, first.id(), UUID.randomUUID().toString()));
    }

    @Test void controllerExposesAgreedPreviewAndPollingContract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service)).build();
        mvc.perform(post("/api/workspaces/{workspaceId}/investigation-plans/preview", workspace)
                .contentType("application/json").content("{\"bundleId\":\"" + bundle.id() + "\",\"provider\":\"CODEX\",\"requestedModel\":\"model-one\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("CODEX"))
                .andExpect(jsonPath("$.maxCalls").value(1)).andExpect(jsonPath("$.maxToolCalls").value(1));
        mvc.perform(get("/api/workspaces/{workspaceId}/investigation-plans/jobs", workspace).param("bundleId", bundle.id()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(model);
    }
}
