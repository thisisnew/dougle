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
class GenerationComparisonIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-comparison-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired GenerationComparisonService comparison;
    @Autowired KnowledgeService knowledge;
    @Autowired PublicationService publication;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ProposalDecisionService decisions;
    String workspace;
    String entry;
    String original;
    String result;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("synthetic comparison").id();
        entry = library.upload(workspace, null, "fixture.txt", "synthetic evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)).id();
        var document = knowledge.create(workspace, "original", "base [E1]", List.of(new Citation(entry, 1, 1)));
        original = document.card().id();
        result = knowledge.createGenerated(document, "AI fixture", "generated fixture [E1]").card().id();
        jdbc.update("INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,result_card_id,created_at,updated_at) VALUES('job',?,?,'CODEX',1,'fixture','SUCCEEDED',?,'now','now')",
                workspace, original, result);
    }

    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void comparesImmutableBaseAndFirstResultWithoutChangingVersionsOrPublication() {
        publication.review(workspace, original, 1, "synthetic", "checked fixture", true, true);
        var published = publication.publish(workspace, original, 1, 0).publication();
        knowledge.revise(workspace, original, 1, "edited original", "human edit [E1]", null);
        knowledge.revise(workspace, result, 1, "edited result", "result edit [E1]", null);
        var compared = comparison.compare(workspace, "job");
        assertThat(compared.base().version().markdown()).isEqualTo("base [E1]");
        assertThat(compared.current().version().markdown()).isEqualTo("human edit [E1]");
        assertThat(compared.generated().version().markdown()).isEqualTo("generated fixture [E1]");
        assertThat(compared.generated().card().latestVersion()).isEqualTo(2);
        assertThat(compared.base().version().evidence()).isEqualTo(compared.generated().version().evidence());
        assertThat(publication.state(workspace, original).publication()).isEqualTo(published);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM generation_job", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void comparesHistoricalEvidenceAfterOriginalFileDeletion() {
        library.deleteEntry(workspace, entry);
        var compared = comparison.compare(workspace, "job");
        assertThat(compared.generated().version().evidence().getFirst().freshness()).isEqualTo("MISSING");
        assertThat(compared.base().version().evidence().getFirst().quote()).isEqualTo("synthetic evidence");
    }

    @Test
    void isolatesWorkspacesAndRejectsUnfinishedJobs() {
        fails(404, () -> comparison.compare(library.createWorkspace("other").id(), "job"));
        fails(404, () -> comparison.compare(workspace, "missing"));
        jdbc.update("UPDATE generation_job SET status='FAILED'");
        fails(409, () -> comparison.compare(workspace, "job"));
    }

    @Test
    void deletedCardsAreNotRecreatedByComparison() {
        knowledge.delete(workspace, result);
        fails(404, () -> comparison.compare(workspace, "job"));
        assertThat(knowledge.list(workspace)).hasSize(1);
        knowledge.delete(workspace, original);
        fails(404, () -> comparison.compare(workspace, "job"));
        assertThat(knowledge.list(workspace)).isEmpty();
    }

    @Test
    void exposesReadOnlyHttpComparison() throws Exception {
        String path = "/api/workspaces/" + workspace + "/generation-jobs/job/comparison";
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.base.version.number").value(1))
                .andExpect(jsonPath("$.current.card.id").value(original))
                .andExpect(jsonPath("$.generated.card.id").value(result));
        mvc.perform(post(path).header("X-Forge-Client", "1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void recordsRejectionAndReopeningWithoutChangingDocumentsOrPublication() {
        publication.review(workspace, original, 1, "synthetic", "checked", true, true);
        publication.publish(workspace, original, 1, 0);
        assertThat(decisions.history(workspace, "job")).isEmpty();
        var rejected = decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", "not useful");
        assertThat(rejected.getFirst().revision()).isEqualTo(1);
        assertThat(rejected.getFirst().note()).isEqualTo("not useful");
        var reopened = decisions.decide(workspace, "job", 1, 1, 1, "OPEN", "reconsider");
        assertThat(reopened).extracting(item -> item.status()).containsExactly("OPEN", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version", Integer.class)).isEqualTo(2);
        assertThat(publication.published(workspace, original).version().number()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM generation_job", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test
    void staleDecisionsAndChangedDocumentsCannotBeSilentlyOverwritten() {
        decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", "first");
        fails(409, () -> decisions.decide(workspace, "job", 0, 1, 1, "OPEN", "stale"));
        fails(409, () -> decisions.decide(workspace, "job", 1, 1, 1, "REJECTED", "duplicate"));
        knowledge.revise(workspace, original, 1, "changed", "human [E1]", null);
        fails(409, () -> decisions.decide(workspace, "job", 1, 1, 1, "OPEN", "old view"));
        knowledge.revise(workspace, result, 1, "changed result", "human result [E1]", null);
        fails(409, () -> decisions.decide(workspace, "job", 1, 2, 1, "OPEN", "old result"));
        assertThat(decisions.decide(workspace, "job", 1, 2, 2, "OPEN", "reviewed again")).hasSize(2);
    }

    @Test
    void validatesDecisionInputAndWorkspaceBeforeWriting() {
        fails(404, () -> decisions.decide(library.createWorkspace("other").id(), "job", 0, 1, 1, "REJECTED", "no"));
        fails(400, () -> decisions.decide(workspace, "job", 0, 1, 1, "ACCEPTED", "unsupported"));
        fails(400, () -> decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", " "));
        fails(400, () -> decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", "x".repeat(4001)));
        jdbc.update("UPDATE generation_job SET status='FAILED'");
        fails(409, () -> decisions.history(workspace, "job"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_decision", Integer.class)).isZero();
    }

    @Test
    void keepsHistoryAfterCardDeletionButDoesNotRecreateCards() {
        decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", "keep history");
        knowledge.delete(workspace, result);
        assertThat(decisions.history(workspace, "job")).hasSize(1);
        fails(404, () -> decisions.decide(workspace, "job", 1, 1, 1, "OPEN", "deleted"));
        jdbc.update("DELETE FROM workspace WHERE id=?", workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_decision", Integer.class)).isZero();
    }

    @Test
    void rejectionWithMissingEvidenceDoesNotMarkKnowledgeAsVerified() {
        library.deleteEntry(workspace, entry);
        decisions.decide(workspace, "job", 0, 1, 1, "REJECTED", "missing evidence");
        assertThat(comparison.compare(workspace, "job").generated().version().evidence().getFirst().freshness()).isEqualTo("MISSING");
        assertThat(publication.state(workspace, original).review()).isNull();
        assertThat(publication.state(workspace, original).publication()).isNull();
    }

    @Test
    void decisionsUseSeparateHttpEndpointAndRequireClientHeader() throws Exception {
        String path = "/api/workspaces/" + workspace + "/generation-jobs/job/decisions";
        String body = """
                {"expectedRevision":0,"expectedSourceVersion":1,"expectedResultVersion":1,"status":"REJECTED","note":"synthetic"}
                """;
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("REJECTED"));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$[0].revision").value(1));
    }
}
