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
    @MockitoSpyBean SearchService search;
    @Autowired InvestigationPlanPreview planPreviews;
    @Autowired dev.knowledgeforge.investigation.adapter.ConversationOperationalReadService operationalReads;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PlanModel model;
    @MockitoSpyBean LocalInvestigationTools localTools;
    @MockitoSpyBean dev.knowledgeforge.plugins.adapter.MysqlClient mysqlRead;
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

    private String mysqlCandidate() {
        return """
                {"decision":"PROPOSE_MYSQL_READ","query":null,"candidate":{"table":null,"columns":null,"equalities":null,"limit":null}}
                """;
    }

    @Test void completedScopeCancellationRequiresExactRevisionAndOwnershipAndPreservesPlanReceipt() throws Exception {
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var completed = terminal(service.start(workspace, startRequest(preview())).id());
        var scope = service.scope(workspace, completed.id());
        rejects(400, () -> service.cancelScope(workspace, completed.id(), 0));
        rejects(409, () -> service.cancelScope(workspace, completed.id(), scope.revision() + 1));
        rejects(404, () -> service.cancelScope("other", completed.id(), scope.revision()));
        assertThat(service.requireCurrent(workspace, scope.id())).isEqualTo(scope);
        var mvc = MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service))
                .setControllerAdvice(new dev.knowledgeforge.library.adapter.web.ApiErrors()).build();
        String route = "/api/workspaces/" + workspace + "/investigation-plans/jobs/" + completed.id() + "/scope/cancel";
        for (String body : List.of("{}", "null", "{\"expectedRevision\":null}", "{\"expectedRevision\":\"1\"}",
                "{\"expectedRevision\":1.5}", "{\"expectedRevision\":1,\"expectedRevision\":1}",
                "{\"expectedRevision\":1,\"connectionGeneration\":\"client\"}", "{\"expectedRevision\":1}{}")) {
            mvc.perform(post(route).contentType("application/json").content(body)).andExpect(status().isBadRequest());
            assertThat(service.requireCurrent(workspace, scope.id())).isEqualTo(scope);
        }
        mvc.perform(post(route).contentType("application/json").content(" ".repeat(513))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post(route).contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isNoContent());
        rejects(404, () -> service.clarification(workspace, completed.id()));
        rejects(404, () -> service.requireCurrent(workspace, scope.id()));
        assertThat(service.get(workspace, completed.id())).isEqualTo(completed);
        verifyNoInteractions(localTools, mysqlRead);
    }



    @Test void plannerCaptureUsesExactTypedSnapshotAndNeverPerformsASecondSearchOrHoldsPlanMonitor() throws Exception {
        clearInvocations(search);
        var completed = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        verify(search, times(1)).searchQuestion(eq(workspace), eq("telescope"), anyList(), eq(false));
        var snapshot = localTools.snapshot(workspace, completed.id(), completed.query());
        doAnswer(call -> {
            assertThat(Thread.holdsLock(service)).isFalse();
            return snapshot;
        }).when(localTools).snapshot(workspace, completed.id(), completed.query());
        clearInvocations(search);
        String requestId = UUID.randomUUID().toString();
        var captured = service.capture(workspace, completed.id(), requestId);
        assertThat(captured.payload().items()).extracting(EvidenceBundle.Item::quote)
                .containsExactlyElementsOf(snapshot.entries().stream().map(entry -> entry.hit().snippet()).toList());
        assertThat(service.capture(workspace, completed.id(), requestId)).isEqualTo(captured);
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
        verify(search, never()).searchQuestionCandidates(anyString(), anyString());
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
    }

    @Test void missingSnapshotCannotFallbackToNewSearchOrCommitAnotherTurn() throws Exception {
        var completed = terminal(service.start(workspace, startRequest(preview())).id());
        doThrow(new LibraryException(409, "synthetic expired snapshot")).when(localTools)
                .snapshot(workspace, completed.id(), completed.query());
        clearInvocations(search);
        rejects(409, () -> service.capture(workspace, completed.id(), UUID.randomUUID().toString()));
        assertThat(threads.get(workspace, bundle.payload().chatContext().threadId()).bundles()).hasSize(1);
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
        verify(search, never()).searchQuestionCandidates(anyString(), anyString());
    }



    @Test void typedPlanCreatesBusinessOnlyClarificationWithoutPersistingTechnicalCandidateOrExecuting() throws Exception {
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var completed = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.toolStatus()).isEqualTo("PENDING_SCOPE");
        var clarification = service.clarification(workspace, completed.id());
        assertThat(clarification.threadId()).isEqualTo(bundle.payload().chatContext().threadId());
        assertThat(clarification.parentBundleId()).isEqualTo(bundle.id());
        assertThat(clarification.executionAllowed()).isFalse();
        assertThat(clarification.internalResolutionRequired()).isTrue();
        assertThat(clarification.questions()).extracting(question -> question.topic().name())
                .isEmpty();
        assertThat(completed.output()).isEqualTo(clarification.scopeId()).matches("[a-f0-9-]{36}");
        assertThat(service.scope(workspace, completed.id()).executionAllowed()).isFalse();
        var mvc = MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service))
                .setControllerAdvice(new dev.knowledgeforge.library.adapter.web.ApiErrors()).build();
        mvc.perform(get("/api/workspaces/{workspaceId}/investigation-plans/jobs/{id}/clarification", workspace, completed.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executionAllowed").value(false))
                .andExpect(jsonPath("$.candidate").doesNotExist()).andExpect(jsonPath("$.connectionGeneration").doesNotExist())
                .andExpect(jsonPath("$.requiredFields").doesNotExist());
        rejects(404, () -> service.clarification("other", completed.id()));
        rejects(409, () -> service.capture(workspace, completed.id(), UUID.randomUUID().toString()));
        verifyNoInteractions(localTools, mysqlRead);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval", Integer.class)).isZero();
    }

    @Test void naturalClarificationRetainsOriginalTaskAndRequiresFreshApprovalWithoutOldScopeInheritance() throws Exception {
        String original = bundle.payload().question();
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var old = terminal(service.start(workspace, startRequest(preview())).id());
        bundle = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(),
                bundle.payload().chatContext().threadId(), bundle.id(), "주문 번호는 SYN42입니다", List.of()));
        rejects(409, () -> service.clarification(workspace, old.id()));
        var nextPreview = preview();
        assertThat(nextPreview.prompt()).contains(original, "SYN42", "Keep the original task", "never ask users for SQL");
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var next = terminal(service.start(workspace, startRequest(nextPreview)).id());
        var clarification = service.clarification(workspace, next.id());
        assertThat(clarification.parentBundleId()).isEqualTo(bundle.id());
        assertThat(clarification.scopeId()).isNotEqualTo(old.output());
        assertThat(clarification.questions()).extracting(question -> question.topic().name()).isEmpty();
        verifyNoInteractions(localTools, mysqlRead);
    }



    @Test void failedSqlitePublicationRollsBackJobAndRemovesUnpublishedMemoryCandidate() throws Exception {
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var scopes = ReflectionTestUtils.getField(service, "scopes");
        var pending = (java.util.Map<?, ?>) ReflectionTestUtils.getField(scopes, "pending");
        int before = pending.size();
        jdbc.execute("CREATE TEMP TRIGGER scope_fail BEFORE UPDATE ON investigation_plan WHEN NEW.tool_status='PENDING_SCOPE' BEGIN SELECT RAISE(ABORT, 'synthetic rollback'); END");
        try {
            var failed = terminal(service.start(workspace, startRequest(preview())).id());
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.output()).isNull();
            assertThat(pending).hasSize(before);
            rejects(409, () -> service.clarification(workspace, failed.id()));
        } finally { jdbc.execute("DROP TRIGGER scope_fail"); }
        verifyNoInteractions(localTools, mysqlRead);
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
        verifyNoInteractions(model, localTools, mysqlRead);
    }

    @Test void promptRestrictsRoutineAndAmbiguousIntentAndInvalidatesOldPlanBinding() {
        var prepared = preview();
        assertThat(prepared.prompt()).contains("ONLY when the user's question explicitly requests actual current records",
                "existing evidence is insufficient", "Policy definitions, code flow, design patterns",
                "Mentions of databases or logs in documents are NOT operational intent",
                "Image-only or ambiguous intent must use SEARCH or STOP", "Do not guess SQL");
        jdbc.update("UPDATE investigation_plan SET binding_digest='LOCAL_INVESTIGATION_PLAN_V3' WHERE digest=?", prepared.digest());
        rejects(409, () -> service.start(workspace, startRequest(prepared)));
        verifyNoInteractions(model, localTools, mysqlRead);
    }

    @Test void proposalsPersistAndRecoverWithoutExecutorsOrOperationalNetworkClients() throws Exception {
        for (var kind : List.of(dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool.MYSQL_READ)) {
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
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
        verifyNoInteractions(localTools, mysqlRead);
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
            return "{\"decision\":\"PROPOSE_MYSQL_READ\",\"query\":null}";
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
        verifyNoInteractions(localTools, mysqlRead);
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

    private InvestigationPlanStore.Job captureForContinuation() throws Exception {
        var searched = terminal(service.start(workspace, startRequest(preview())).id());
        bundle = service.capture(workspace, searched.id(), UUID.randomUUID().toString());
        clearInvocations(model, localTools);
        return searched;
    }

    @Test void continuationRequiresFreshConsentAndPublishesTypedPreviewAndCandidateWithoutTools() throws Exception {
        var searched = captureForContinuation();
        var root = bundle.payload().chatContext().parentBundleId();
        var prepared = preview();
        assertThat(prepared.mode()).isEqualTo("RESOLVE_CAPTURED_SCOPE");
        assertThat(prepared.rootQuestionBundleId()).isEqualTo(root);
        assertThat(prepared.maxCalls()).isEqualTo(1);
        assertThat(prepared.maxToolCalls()).isZero();
        assertThat(prepared.prompt()).contains("SEARCH is forbidden", "telescope");
        assertThat(store.capture(workspace, bundle.id()).orElseThrow().jobId()).isEqualTo(searched.id());
        assertThat(store.continuationConsumed(workspace, root)).isFalse();
        var request = startRequest(prepared);
        rejects(403, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(),
                request.provider(), request.requestedModel(), request.digest(), false)));
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(),
                "CLAUDE", request.requestedModel(), request.digest(), true)));
        rejects(409, () -> service.start(workspace, new InvestigationPlanService.Start(request.requestId(), bundle.id(),
                request.provider(), "different-model", request.digest(), true)));
        String other = library.createWorkspace("other continuation").id();
        rejects(404, () -> service.preview(other, new InvestigationPlanService.PreviewRequest(bundle.id(), "CODEX", "model-one")));
        when(model.generate(anyString(), anyString(), anyString())).thenReturn(mysqlCandidate());
        var completed = terminal(service.start(workspace, request).id());
        assertThat(completed.toolStatus()).isEqualTo("PENDING_SCOPE");
        assertThat(service.scope(workspace, completed.id()).binding().parentBundleId()).isEqualTo(bundle.id());
        assertThat(store.continuationConsumed(workspace, root)).isTrue();
        assertThat(service.start(workspace, request)).isEqualTo(completed);
        rejects(409, this::preview);
        verify(model, times(1)).generate(anyString(), anyString(), anyString());
        verifyNoInteractions(localTools, mysqlRead);
    }

    @Test void continuationSearchFailsWithoutToolDispatchAndDoesNotRefundOrReplayAfterRestart() throws Exception {
        captureForContinuation();
        var first = startRequest(preview());
        var second = startRequest(preview());
        var failed = terminal(service.start(workspace, first).id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.output()).isNull();
        rejects(409, () -> service.start(workspace, second));
        new JdbcInvestigationPlanStore(jdbc);
        clearInvocations(model);
        assertThat(service.start(workspace, first)).isEqualTo(failed);
        assertThat(service.list(workspace, bundle.id())).containsExactly(failed);
        rejects(409, this::preview);
        verifyNoInteractions(model, localTools);
    }

    @Test void continuationStopConsumesAllowanceEvenIfCapturedBundleAndJobAreDeleted() throws Exception {
        captureForContinuation();
        String root = bundle.payload().chatContext().parentBundleId();
        when(model.generate(anyString(), anyString(), anyString())).thenReturn("{\"decision\":\"STOP\",\"query\":null,\"candidate\":null}");
        var completed = terminal(service.start(workspace, startRequest(preview())).id());
        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.decision()).isEqualTo("STOP");
        jdbc.update("DELETE FROM evidence_bundle WHERE id=?", bundle.id());
        assertThat(store.continuationConsumed(workspace, root)).isTrue();
        assertThat(store.claimContinuation(workspace, root, UUID.randomUUID().toString())).isFalse();
        bundle = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(),
                bundles.get(workspace, root).payload().chatContext().threadId(), root, "a new user question", List.of()));
        assertThat(preview().mode()).isEqualTo("LOCAL_PLAN");
        verifyNoInteractions(localTools);
    }

    @Test void cancelledContinuationNeverRefundsAndLateProposalIsDiscarded() throws Exception {
        captureForContinuation();
        blockIgnoringInterrupts();
        var request = startRequest(preview());
        var job = service.start(workspace, request);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        service.cancel(workspace, job.id());
        release.countDown();
        var cancelled = terminal(job.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.output()).isNull();
        rejects(409, this::preview);
        assertThat(service.start(workspace, request)).isEqualTo(cancelled);
        verifyNoInteractions(localTools);
    }

    @Test void continuationRejectsChangedParentBeforeStartAndBeforePublishing() throws Exception {
        captureForContinuation();
        var oldBundle = bundle;
        var request = startRequest(preview());
        threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(),
                bundle.payload().chatContext().threadId(), bundle.id(), "new correction", List.of()));
        rejects(409, () -> service.start(workspace, request));
        assertThat(store.continuationConsumed(workspace, oldBundle.payload().chatContext().parentBundleId())).isFalse();
        verifyNoInteractions(model);
    }

    @Test void continuationDiscardsLateResultWhenParentChangesDuringModelCall() throws Exception {
        captureForContinuation();
        when(model.generate(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread()); entered.countDown(); release.await(); return mysqlCandidate();
        });
        var request = startRequest(preview());
        var running = service.start(workspace, request);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(),
                bundle.payload().chatContext().threadId(), bundle.id(), "new correction", List.of()));
        release.countDown();
        var failed = terminal(running.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.output()).isNull();
        assertThat(store.continuationConsumed(workspace, bundle.payload().chatContext().parentBundleId())).isTrue();
        verifyNoInteractions(localTools);
    }

    @Test void continuationClaimRollsBackIfApprovalConsumptionFails() throws Exception {
        captureForContinuation();
        var prepared = preview();
        jdbc.update("UPDATE investigation_plan SET status='FAILED' WHERE digest=?", prepared.digest());
        rejects(409, () -> service.start(workspace, startRequest(prepared)));
        assertThat(store.continuationConsumed(workspace, prepared.rootQuestionBundleId())).isFalse();
        verifyNoInteractions(model, localTools);
    }

    @Test void continuationNeedsRealCaptureMappingAndRecoversCaptureWithoutSnapshotAfterRestart() throws Exception {
        var searched = captureForContinuation();
        var mapping = store.capture(workspace, bundle.id()).orElseThrow();
        new JdbcInvestigationPlanStore(jdbc);
        assertThat(service.capture(workspace, searched.id(), mapping.requestId())).isEqualTo(bundle);
        verifyNoInteractions(localTools, model);
        jdbc.update("DELETE FROM investigation_capture WHERE bundle_id=?", bundle.id());
        rejects(409, this::preview);
    }

    @Test void durableContinuationClaimSurvivesRestartOfAnUnfinishedApprovedJob() throws Exception {
        captureForContinuation();
        var prepared = preview();
        String requestId = UUID.randomUUID().toString();
        assertThat(store.claimContinuation(workspace, prepared.rootQuestionBundleId(), requestId)).isTrue();
        assertThat(store.consume(workspace, prepared.digest(), requestId, Instant.now().toString())).isTrue();
        new JdbcInvestigationPlanStore(jdbc);
        var request = new InvestigationPlanService.Start(requestId, bundle.id(), prepared.provider(), prepared.requestedModel(), prepared.digest(), true);
        assertThat(service.start(workspace, request).status()).isEqualTo("FAILED");
        rejects(409, this::preview);
        verifyNoInteractions(model, localTools);
    }

    @Test void continuationControllerPublishesFreshApprovalModeAndZeroToolBudget() throws Exception {
        captureForContinuation();
        var mvc = MockMvcBuilders.standaloneSetup(new InvestigationPlanController(service)).build();
        mvc.perform(post("/api/workspaces/{workspaceId}/investigation-plans/preview", workspace)
                .contentType("application/json").content("{\"bundleId\":\"" + bundle.id() + "\",\"provider\":\"CODEX\",\"requestedModel\":\"model-one\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("RESOLVE_CAPTURED_SCOPE"))
                .andExpect(jsonPath("$.rootQuestionBundleId").value(bundle.payload().chatContext().parentBundleId()))
                .andExpect(jsonPath("$.maxCalls").value(1)).andExpect(jsonPath("$.maxToolCalls").value(0));
        verifyNoInteractions(model, localTools);
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
