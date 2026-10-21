package dev.knowledgeforge;

import dev.knowledgeforge.conversation.application.AnswerApprovalService;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.search.application.EvidenceAnswerPreview;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class AnswerApprovalIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-answer-approval-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired AnswerApprovalService approvals;
    @Autowired EvidenceAnswerPreview previews;
    @Autowired EvidenceBundleService bundles;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean CuratorModel model;
    String workspace;
    String entry;
    EvidenceBundle bundle;
    EvidenceAnswerPreview.Preview preview;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("synthetic approval").id();
        entry = library.upload(workspace, null, "fixture.txt", "synthetic telescope".getBytes(java.nio.charset.StandardCharsets.UTF_8)).id();
        search.refresh();
        bundle = bundles.create(workspace, "telescope", List.of(), true);
        preview = previews.prepare(workspace, bundle.id(), "CODEX", "model-one");
    }

    @AfterEach
    void noExternalCalls() { verifyNoInteractions(model); }

    AnswerApprovalService.State approve(String expected) {
        return approvals.approve(workspace, bundle.id(), "CODEX", "model-one", preview.digest(), true, expected);
    }

    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void explicitApprovalBindsPreviewForTenMinutesWithoutDispatchOrBundleMutation() {
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("NONE");
        var state = approve(null);
        assertThat(state.status()).isEqualTo("APPROVED");
        assertThat(state.approval().digest()).isEqualTo(preview.digest());
        assertThat(state.approval().requestedModel()).isEqualTo("model-one");
        assertThat(Duration.between(Instant.parse(state.approval().approvedAt()), Instant.parse(state.approval().expiresAt()))).isEqualTo(Duration.ofMinutes(10));
        assertThat(approvals.get(workspace, bundle.id())).isEqualTo(state);
        assertThat(bundles.get(workspace, bundle.id())).isEqualTo(bundle);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isZero();
    }

    @Test
    void rejectsMissingConsentAndWrongProviderModelDigestWithoutWriting() {
        fails(400, () -> approvals.approve(workspace, bundle.id(), "CODEX", "model-one", preview.digest(), false, null));
        fails(409, () -> approvals.approve(workspace, bundle.id(), "CLAUDE", "model-one", preview.digest(), true, null));
        fails(409, () -> approvals.approve(workspace, bundle.id(), "CODEX", "model-two", preview.digest(), true, null));
        fails(409, () -> approvals.approve(workspace, bundle.id(), "CODEX", "model-one", null, true, null));
        fails(400, () -> approvals.approve(workspace, bundle.id(), "invalid", "model-one", preview.digest(), true, null));
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("NONE");
    }

    @Test
    void staleTabsCannotReplaceOrRevokeNewApprovalAndRetriesDoNotExtendExpiry() {
        var first = approve(null);
        fails(409, () -> approve(null));
        assertThat(approvals.get(workspace, bundle.id())).isEqualTo(first);
        var replacement = approve(first.approval().id());
        assertThat(replacement.approval().id()).isNotEqualTo(first.approval().id());
        fails(409, () -> approve(first.approval().id()));
        fails(409, () -> approvals.revoke(workspace, bundle.id(), first.approval().id()));
        assertThat(approvals.get(workspace, bundle.id())).isEqualTo(replacement);
    }

    @Test
    void revocationIsIdempotentAndReapprovalRequiresLatestId() {
        var first = approve(null);
        var revoked = approvals.revoke(workspace, bundle.id(), first.approval().id());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(approvals.revoke(workspace, bundle.id(), first.approval().id())).isEqualTo(revoked);
        assertThat(approvals.get(workspace, bundle.id())).isEqualTo(revoked);
        fails(409, () -> approve(null));
        assertThat(approve(first.approval().id()).status()).isEqualTo("APPROVED");
    }

    @Test
    void expiredApprovalIsNotReadyAndLookupDoesNotRenewIt() {
        var first = approve(null);
        jdbc.update("UPDATE answer_approval SET expires_at=?", Instant.now().minusSeconds(1).toString());
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("EXPIRED");
        assertThat(approvals.get(workspace, bundle.id()).approval().id()).isEqualTo(first.approval().id());
    }

    @Test
    void deletionInvalidatesApprovalAndReapprovalIsBlockedButRevocationStillWorks() {
        var first = approve(null);
        library.deleteEntry(workspace, entry);
        assertThat(approvals.get(workspace, bundle.id()).status()).isEqualTo("INVALIDATED");
        fails(409, () -> approve(first.approval().id()));
        assertThat(approvals.revoke(workspace, bundle.id(), first.approval().id()).status()).isEqualTo("REVOKED");
    }

    @Test
    void changedPromptBindingIsInvalidatedWithoutRewritingStoredApproval() {
        var first = approve(null);
        jdbc.update("UPDATE answer_approval SET digest='previous-prompt-version'");
        var state = approvals.get(workspace, bundle.id());
        assertThat(state.status()).isEqualTo("INVALIDATED");
        assertThat(state.approval().id()).isEqualTo(first.approval().id());
        assertThat(state.approval().digest()).isEqualTo("previous-prompt-version");
    }

    @Test
    void workspaceIsolationAndBundleDeletionCascade() {
        var first = approve(null);
        String other = library.createWorkspace("other").id();
        fails(404, () -> approvals.get(other, bundle.id()));
        fails(404, () -> approvals.approve(other, bundle.id(), "CODEX", "model-one", preview.digest(), true, first.approval().id()));
        fails(404, () -> approvals.revoke(other, bundle.id(), first.approval().id()));
        bundles.delete(workspace, bundle.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
        fails(404, () -> approvals.get(workspace, bundle.id()));
    }

    @Test
    void httpRequiresExplicitConsentAndMutationHeaders() throws Exception {
        String path = "/api/workspaces/" + workspace + "/evidence-bundles/" + bundle.id() + "/answer-approval";
        String body = "{\"provider\":\"CODEX\",\"requestedModel\":\"model-one\",\"digest\":\"" + preview.digest() + "\",\"acknowledged\":true}";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NONE"));
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json").content(body.replace(",\"acknowledged\":true", ""))).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        var approval = approvals.get(workspace, bundle.id()).approval();
        mvc.perform(delete(path + "/" + approval.id())).andExpect(status().isForbidden());
        mvc.perform(delete(path + "/" + approval.id()).header("X-Forge-Client", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
    }
}
