package dev.dougle;

import dev.dougle.conversation.application.*;
import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.library.application.*;
import dev.dougle.search.application.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ConversationIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-conversation-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired ConversationService conversations;
    @Autowired ConversationPreviewService contextPreviews;
    @Autowired AnswerApprovalService approvals;
    @Autowired ConversationRequestService requests;
    @Autowired ConversationRequestStore requestStore;
    @Autowired EvidenceAnswerPreview answerPreviews;
    @Autowired AnswerJobStore jobs;
    @Autowired LibraryService library;
    @Autowired EvidenceBundleService bundles;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean AnswerModel model;
    String workspace;
    String bundle;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("synthetic room").id();
        library.upload(workspace, null, "fixture.txt", "synthetic telescope".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        search.refresh();
        bundle = bundles.create(workspace, "telescope", List.of(), false).id();
        seed("first", "SUCCEEDED");
        seed("second", "SUCCEEDED");
    }
    void seed(String id, String status) {
        jobs.create(new AnswerJob(id, workspace, bundle, id, "CODEX", "fixture", "digest", status,
                status.equals("SUCCEEDED") ? "synthetic answer [R1]" : null, null, "now", "now"));
    }
    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }
    @AfterEach
    void noGeneration() { verifyNoInteractions(model); }

    @Test
    void explicitOrderAndIdempotentRetryDoNotChangeJobsOrApprovals() {
        var room = conversations.create(workspace, "  관측  ");
        assertThat(room.title()).isEqualTo("관측");
        conversations.append(workspace, room.id(), "second", 0);
        var result = conversations.append(workspace, room.id(), "first", 1);
        assertThat(result.turns()).extracting("jobId").containsExactly("second", "first");
        assertThat(result.turns()).extracting("position").containsExactly(1, 2);
        assertThat(conversations.append(workspace, room.id(), "second", 0)).isEqualTo(result);
        assertThat(jobs.find(workspace, "first").orElseThrow().answer()).isEqualTo("synthetic answer [R1]");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
    }
    @Test
    void rejectsStaleRevisionAndIncompleteAnswers() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        fails(409, () -> conversations.append(workspace, room.id(), "second", 0));
        fails(400, () -> conversations.append(workspace, room.id(), "second", null));
        for (String status : List.of("QUEUED", "RUNNING", "CANCELLING", "FAILED", "CANCELLED", "INTERRUPTED")) {
            seed(status, status);
            fails(409, () -> conversations.append(workspace, room.id(), status, 1));
        }
        assertThat(conversations.get(workspace, room.id()).turns()).hasSize(1);
    }
    @Test
    void scopesReadsWritesAndForeignKeysToWorkspace() {
        var room = conversations.create(workspace, "fixture");
        String other = library.createWorkspace("other synthetic").id();
        var otherRoom = conversations.create(other, "other");
        fails(404, () -> conversations.get(other, room.id()));
        fails(404, () -> conversations.delete(other, room.id()));
        fails(404, () -> conversations.append(other, otherRoom.id(), "first", 0));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO conversation_turn VALUES(?,?,?,?,?)", other, otherRoom.id(), "first", 1, "now"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("SQLITE_CONSTRAINT_FOREIGNKEY");
        assertThat(conversations.list(other)).hasSize(1);
    }
    @Test
    void roomDeletionOnlyRemovesLinksWhileBundleDeletionRemovesJobsAndLinks() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        conversations.delete(workspace, room.id());
        assertThat(jobs.find(workspace, "first")).isPresent();
        assertThat(bundles.get(workspace, bundle)).isNotNull();
        var remaining = conversations.create(workspace, "remaining");
        conversations.append(workspace, remaining.id(), "first", 0);
        jdbc.update("DELETE FROM evidence_bundle WHERE id=?", bundle);
        assertThat(conversations.get(workspace, remaining.id()).turns()).isEmpty();
        assertThat(conversations.get(workspace, remaining.id()).conversation().revision()).isEqualTo(1);
        assertThat(jobs.find(workspace, "first")).isEmpty();
    }
    @Test
    void deletedLinksDoNotReusePositionsAndWorkspaceDeletionCascades() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        jdbc.update("DELETE FROM answer_job WHERE id='first'");
        var detail = conversations.append(workspace, room.id(), "second", 1);
        assertThat(detail.turns()).extracting("position").containsExactly(2);
        library.deleteWorkspace(workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_turn", Integer.class)).isZero();
    }
    @Test
    void validatesNamesAndWorkspaceLimit() {
        for (String title : new String[] {null, "", " ", "x".repeat(121), "bad\nname"}) fails(400, () -> conversations.create(workspace, title));
        fails(404, () -> conversations.create("missing", "fixture"));
        for (int index = 0; index < 100; index++) conversations.create(workspace, "synthetic " + index);
        fails(409, () -> conversations.create(workspace, "overflow"));
    }
    @Test
    void concurrentAppendsWithSameRevisionOnlyAcceptOne() throws Exception {
        var room = conversations.create(workspace, "fixture");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (String jobId : List.of("first", "second")) results.add(executor.submit(() -> {
                start.await();
                try { conversations.append(workspace, room.id(), jobId, 0); return 200; }
                catch (LibraryException error) { return error.status(); }
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(conversations.get(workspace, room.id()).turns()).hasSize(1);
    }
    @Test
    void limitsLinksAndAllowsSafeRetryAtCapacity() {
        var room = conversations.create(workspace, "fixture");
        for (int index = 0; index < 100; index++) {
            seed("fixture-" + index, "SUCCEEDED");
            conversations.append(workspace, room.id(), "fixture-" + index, index);
        }
        fails(409, () -> conversations.append(workspace, room.id(), "first", 100));
        assertThat(conversations.append(workspace, room.id(), "fixture-0", 0).turns()).hasSize(100);
    }
    @Test
    void freezesFreshBundleIdempotentlyAndEnablesOnlyItsBoundApproval() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        String current = bundles.create(workspace, "telescope", List.of(), false).id();
        var selection = new ConversationPreviewService.Request(current, 1, List.of("first"), "CODEX", "fixture");
        var preview = contextPreviews.prepare(workspace, room.id(), selection);
        fails(409, () -> approvals.approve(workspace, current, "CODEX", "fixture", preview.digest(), true, null));
        var frozen = requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(selection, preview.digest()));
        assertThat(requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(selection, preview.digest()))).isEqualTo(frozen);
        assertThat(answerPreviews.prepare(workspace, current, "CODEX", "fixture").contextRequestId()).isEqualTo(frozen.id());
        assertThat(answerPreviews.prepare(workspace, current, "CODEX", "fixture").prompt()).isEqualTo(preview.prompt());
        assertThat(approvals.approve(workspace, current, "CODEX", "fixture", preview.digest(), true, null).status()).isEqualTo("APPROVED");
        fails(409, () -> answerPreviews.prepare(workspace, current, "CLAUDE", "fixture"));
        fails(409, () -> answerPreviews.prepare(workspace, current, "CODEX", "other"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_job", Integer.class)).isEqualTo(2);
    }
    @Test
    void rejectsChangingUsedBundlesAndInvalidatesDeletedRoomWithoutLosingFrozenAudit() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        var used = new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CODEX", "fixture");
        var usedPreview = contextPreviews.prepare(workspace, room.id(), used);
        fails(409, () -> requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(used, usedPreview.digest())));
        String current = bundles.create(workspace, "telescope", List.of(), false).id();
        var selection = new ConversationPreviewService.Request(current, 1, List.of("first"), "CODEX", "fixture");
        var preview = contextPreviews.prepare(workspace, room.id(), selection);
        fails(409, () -> requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(selection, "wrong")));
        var frozen = requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(selection, preview.digest()));
        approvals.approve(workspace, current, "CODEX", "fixture", preview.digest(), true, null);
        conversations.delete(workspace, room.id());
        assertThat(approvals.get(workspace, current).status()).isEqualTo("INVALIDATED");
        assertThat(requestStore.find(workspace, current)).contains(frozen);
        jdbc.update("DELETE FROM evidence_bundle WHERE id=?", current);
        assertThat(requestStore.find(workspace, current)).isEmpty();
    }
    @Test
    void refusesDifferentContextOrPreexistingApprovalForSameBundle() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        conversations.append(workspace, room.id(), "second", 1);
        String current = bundles.create(workspace, "telescope", List.of(), false).id();
        var first = new ConversationPreviewService.Request(current, 2, List.of("first"), "CODEX", "fixture");
        requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(first, contextPreviews.prepare(workspace, room.id(), first).digest()));
        var second = new ConversationPreviewService.Request(current, 2, List.of("second"), "CODEX", "fixture");
        fails(409, () -> requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(second, contextPreviews.prepare(workspace, room.id(), second).digest())));
        String approved = bundles.create(workspace, "telescope", List.of(), false).id();
        var base = answerPreviews.prepare(workspace, approved, "CODEX", "fixture");
        approvals.approve(workspace, approved, "CODEX", "fixture", base.digest(), true, null);
        var blocked = new ConversationPreviewService.Request(approved, 2, List.of("first"), "CODEX", "fixture");
        fails(409, () -> requests.freeze(workspace, room.id(), new ConversationRequestService.Freeze(blocked, contextPreviews.prepare(workspace, room.id(), blocked).digest())));
    }
    @Test
    void contextPreviewUsesExplicitSelectionInShelfOrderWithoutWritesOrApproval() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "second", 0);
        conversations.append(workspace, room.id(), "first", 1);
        var request = new ConversationPreviewService.Request(bundle, 2, List.of("first", "second"), "CODEX", "fixture");
        var preview = contextPreviews.prepare(workspace, room.id(), request);
        assertThat(preview.history()).extracting("jobId").containsExactly("second", "first");
        assertThat(preview.prompt()).contains("UNTRUSTED CONTEXT", "MUST NOT be reused", "CURRENT_REQUEST:", "synthetic answer [R1]");
        assertThat(preview.digest()).isEqualTo(contextPreviews.prepare(workspace, room.id(), request).digest());
        assertThat(preview.characters()).isEqualTo(preview.prompt().length()).isLessThanOrEqualTo(preview.maxCharacters());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_job", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
        assertThat(conversations.get(workspace, room.id()).conversation().revision()).isEqualTo(2);
        fails(409, () -> approvals.approve(workspace, bundle, "CODEX", "fixture", preview.digest(), true, null));
    }
    @Test
    void contextDigestBindsModelSelectionAnswerQuestionAndRoomRevision() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        var request = new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CODEX", "fixture");
        var original = contextPreviews.prepare(workspace, room.id(), request).digest();
        assertThat(contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CLAUDE", "fixture")).digest()).isNotEqualTo(original);
        assertThat(contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CODEX", "different")).digest()).isNotEqualTo(original);
        String newBundle = bundles.create(workspace, "synthetic", List.of(), false).id();
        assertThat(contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(newBundle, 1, List.of("first"), "CODEX", "fixture")).digest()).isNotEqualTo(original);
        jdbc.update("UPDATE answer_job SET answer='changed synthetic answer [R1]' WHERE id='first'");
        assertThat(contextPreviews.prepare(workspace, room.id(), request).digest()).isNotEqualTo(original);
        conversations.append(workspace, room.id(), "second", 1);
        fails(409, () -> contextPreviews.prepare(workspace, room.id(), request));
    }
    @Test
    void contextRejectsEmptyDuplicateUnlinkedAndCrossWorkspaceSelections() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        for (var ids : List.of(List.<String>of(), List.of("first", "first"), List.of("1","2","3","4","5","6"))) {
            fails(400, () -> contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(bundle, 1, ids, "CODEX", "fixture")));
        }
        fails(400, () -> contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(bundle, null, List.of("first"), "CODEX", "fixture")));
        fails(409, () -> contextPreviews.prepare(workspace, room.id(), new ConversationPreviewService.Request(bundle, 1, List.of("second"), "CODEX", "fixture")));
        String other = library.createWorkspace("other preview").id();
        fails(404, () -> contextPreviews.prepare(other, room.id(), new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CODEX", "fixture")));
    }
    @Test
    void contextChecksHistoricalEvidenceEvenWhenCurrentEvidenceIsFresh() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        library.upload(workspace, null, "new.txt", "independent observatory".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        search.refresh();
        String current = bundles.create(workspace, "observatory", List.of(), false).id();
        var request = new ConversationPreviewService.Request(current, 1, List.of("first"), "CODEX", "fixture");
        assertThat(contextPreviews.prepare(workspace, room.id(), request).history()).hasSize(1);
        jdbc.update("UPDATE entry SET content=X'616263' WHERE workspace_id=? AND name='fixture.txt'", workspace);
        assertThatThrownBy(() -> contextPreviews.prepare(workspace, room.id(), request))
                .isInstanceOf(LibraryException.class).hasMessageContaining("이전 답변의 근거");
    }
    @Test
    void contextRejectsDeletedAndStaleHistoryAndOversizedPrompt() {
        var room = conversations.create(workspace, "fixture");
        conversations.append(workspace, room.id(), "first", 0);
        var request = new ConversationPreviewService.Request(bundle, 1, List.of("first"), "CODEX", "fixture");
        jdbc.update("UPDATE answer_job SET answer=? WHERE id='first'", "x".repeat(20000));
        fails(413, () -> contextPreviews.prepare(workspace, room.id(), request));
        jdbc.update("UPDATE answer_job SET answer='safe [R1]' WHERE id='first'");
        jdbc.update("UPDATE entry SET content=X'616263' WHERE workspace_id=? AND kind='FILE'", workspace);
        fails(409, () -> contextPreviews.prepare(workspace, room.id(), request));
        jdbc.update("DELETE FROM answer_job WHERE id='first'");
        fails(409, () -> contextPreviews.prepare(workspace, room.id(), request));
    }
    @Test
    void httpEndpointsRequireLocalMutationHeaderAndReturnScopedDetails() throws Exception {
        mvc.perform(post("/api/workspaces/" + workspace + "/conversations").contentType("application/json").content("{\"title\":\"fixture\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspaces/" + workspace + "/conversations").header("X-Forge-Client", "1")
                .contentType("application/json").content("{\"title\":\"fixture\"}")).andExpect(status().isCreated());
        String id = conversations.list(workspace).getFirst().id();
        String path = "/api/workspaces/" + workspace + "/conversations/" + id;
        mvc.perform(post(path + "/turns").header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"jobId\":\"first\",\"expectedRevision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.turns[0].position").value(1));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.conversation.revision").value(1));
        mvc.perform(post(path + "/preview").header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"bundleId\":\"" + bundle + "\",\"expectedRevision\":1,\"jobIds\":[\"first\"],\"provider\":\"CODEX\",\"requestedModel\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history[0].jobId").value("first"));
        mvc.perform(delete(path).header("X-Forge-Client", "1")).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
