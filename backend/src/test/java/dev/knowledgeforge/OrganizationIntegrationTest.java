package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.*;
import dev.knowledgeforge.knowledge.domain.Knowledge.Citation;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrganizationIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-organization-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired OrganizationService organization;
    @Autowired KnowledgeService knowledge;
    @Autowired PublicationService publication;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    String workspace;
    String card;
    String entry;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("synthetic folders").id();
        entry = library.upload(workspace, null, "notes.txt", "synthetic observation".getBytes(java.nio.charset.StandardCharsets.UTF_8)).id();
        card = knowledge.create(workspace, "synthetic card", "synthetic observation [E1]", List.of(new Citation(entry, 1, 1))).card().id();
    }

    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void hierarchyNamesAndDepthAreValidated() throws Exception {
        var root = organization.create(workspace, null, "가상 폴더");
        var child = organization.create(workspace, root.id(), "가상 폴더");
        assertThat(child.parentId()).isEqualTo(root.id());
        for (String name : List.of(" ", ".", "..", "a/b", "a\\b", "a\nb", "x".repeat(121))) {
            fails(400, () -> organization.create(workspace, null, name));
        }
        mvc.perform(post("/api/workspaces/" + workspace + "/knowledge-organization/folders")
                .header("X-Forge-Client", "1").contentType("application/json").content("{\"name\":\"가상 폴더\"}"))
                .andExpect(status().isConflict());
        String parent = child.id();
        for (int depth = 3; depth <= 16; depth++) parent = organization.create(workspace, parent, "level " + depth).id();
        String deepest = parent;
        fails(400, () -> organization.create(workspace, deepest, "too deep"));
    }

    @Test
    void cardPlacementDoesNotChangeVersionsReviewPublicationOrOriginal() {
        var folder = organization.create(workspace, null, "가상 지식");
        var before = knowledge.get(workspace, card, null);
        publication.review(workspace, card, 1, "synthetic reviewer", "checked synthetic fixture", true, true);
        var published = publication.publish(workspace, card, 1, 0);
        assertThat(organization.place(workspace, card, folder.id(), 0).placements().getFirst().revision()).isEqualTo(1);
        assertThat(knowledge.get(workspace, card, null)).isEqualTo(before);
        assertThat(publication.state(workspace, card)).isEqualTo(published);
        assertThat(library.content(workspace, entry)).isNotEmpty();
        var root = organization.place(workspace, card, null, 1).placements().getFirst();
        assertThat(root.folderId()).isNull();
        assertThat(root.revision()).isEqualTo(2);
        fails(409, () -> organization.place(workspace, card, folder.id(), 0));
    }

    @Test
    void onlyEmptyFoldersCanBeDeleted() {
        var root = organization.create(workspace, null, "root");
        var child = organization.create(workspace, root.id(), "child");
        fails(409, () -> organization.delete(workspace, root.id()));
        organization.place(workspace, card, child.id(), 0);
        fails(409, () -> organization.delete(workspace, child.id()));
        organization.place(workspace, card, null, 1);
        organization.delete(workspace, child.id());
        organization.delete(workspace, root.id());
        assertThat(organization.catalog(workspace).folders()).isEmpty();
        assertThat(knowledge.get(workspace, card, null).version().number()).isEqualTo(1);
    }

    @Test
    void workspaceAndOriginalFolderIdsCannotBeUsedAcrossBoundaries() {
        String other = library.createWorkspace("other").id();
        var folder = organization.create(other, null, "other folder");
        String originalFolder = library.createFolder(workspace, null, "original").id();
        fails(404, () -> organization.create(workspace, folder.id(), "wrong"));
        fails(404, () -> organization.create(workspace, originalFolder, "wrong"));
        fails(404, () -> organization.place(workspace, card, folder.id(), 0));
        fails(404, () -> organization.place(other, card, folder.id(), 0));
        fails(404, () -> organization.delete(workspace, folder.id()));
        assertThat(organization.catalog(workspace).folders()).isEmpty();
    }

    @Test
    void deletingCardsAndWorkspacesCleansOnlyTheirOrganization() {
        var folder = organization.create(workspace, null, "folder");
        organization.place(workspace, card, folder.id(), 0);
        knowledge.delete(workspace, card);
        assertThat(organization.catalog(workspace).placements()).isEmpty();
        assertThat(organization.catalog(workspace).folders()).hasSize(1);
        library.deleteWorkspace(workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_folder", Integer.class)).isZero();
    }

    @Test
    void workspaceDeletionCascadesPopulatedNestedFoldersAndPlacement() {
        var root = organization.create(workspace, null, "root");
        var child = organization.create(workspace, root.id(), "child");
        organization.place(workspace, card, child.id(), 0);
        library.deleteWorkspace(workspace);
        for (String table : List.of("knowledge_folder", "knowledge_placement", "knowledge_card")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }

    @Test
    void simultaneousMovesHaveOneWinner() throws Exception {
        var folder = organization.create(workspace, null, "destination");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> move = () -> {
                ready.countDown(); release.await();
                try { organization.place(workspace, card, folder.id(), 0); return 200; }
                catch (LibraryException exception) { return exception.status(); }
            };
            var first = executor.submit(move);
            var second = executor.submit(move);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void restRequiresMutationHeaderAndExplicitRevision() throws Exception {
        String base = "/api/workspaces/" + workspace + "/knowledge-organization";
        mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.folders").isArray());
        mvc.perform(post(base + "/folders").contentType("application/json").content("{\"name\":\"folder\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(base + "/cards/" + card).header("X-Forge-Client", "1").contentType("application/json").content("{\"folderId\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(base + "/cards/" + card).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"folderId\":null,\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.placements[0].revision").value(1));
    }
}
