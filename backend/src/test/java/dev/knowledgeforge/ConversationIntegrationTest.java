package dev.knowledgeforge;

import dev.knowledgeforge.conversation.application.*;
import dev.knowledgeforge.conversation.domain.AnswerJob;
import dev.knowledgeforge.library.application.*;
import dev.knowledgeforge.search.application.*;
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
        mvc.perform(delete(path).header("X-Forge-Client", "1")).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
