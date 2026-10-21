package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.*;
import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CuratorIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-curator-test-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired KnowledgeService knowledge;
    @Autowired CuratorService curator;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean CuratorModel model;
    String workspace;
    String entry;
    Document source;
    static final String VALID = "{\"title\":\"관측\",\"claims\":[{\"text\":\"온도는 21도다.\",\"evidenceIds\":[\"E1\"]}]}";

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("가상 AI 테스트").id();
        entry = library.upload(workspace, null, "private-name.txt", "가상 관측\n온도 21도".getBytes(StandardCharsets.UTF_8)).id();
        source = knowledge.create(workspace, "사람 제목", "사람만의 비공개 메모", List.of(new Citation(entry, 1, 2)));
        when(model.generate(any(), anyString(), anyString())).thenReturn(VALID);
    }

    Document generate(CuratorService.Preview preview) {
        return curator.generate(workspace, source.card().id(), preview.provider(), preview.expectedVersion(), preview.digest(), true);
    }

    @Test
    void perRequestModelOverridesServerAndBindsTheActualInvocation() {
        var service = new CuratorService(knowledge, model, new CuratorSettings("server-model", ""));
        var preview = service.preview(workspace, source.card().id(), "CODEX", " custom-model ");
        assertThat(preview.requestedModel()).isEqualTo("custom-model");
        assertThat(service.preview(workspace, source.card().id(), "CODEX").requestedModel()).isEqualTo("server-model");
        assertThat(service.preview(workspace, source.card().id(), "CODEX", "").requestedModel()).isEmpty();
        assertThatThrownBy(() -> service.generate(workspace, source.card().id(), "CODEX", 1, preview.digest(), true, "other-model"))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
        verifyNoInteractions(model);
        var result = service.generate(workspace, source.card().id(), "CODEX", 1, preview.digest(), true, "custom-model");
        verify(model).generate(eq(CuratorModel.Provider.CODEX), eq(preview.prompt()), eq("custom-model"));
        assertThat(result.version().markdown()).contains("요청 모델: custom-model");
    }

    @Test
    void rejectsModelOptionsBeforeInvokingCli() throws Exception {
        String path = "/api/workspaces/" + workspace + "/knowledge/" + source.card().id() + "/generation/preview";
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"provider\":\"CODEX\",\"requestedModel\":\"--tools\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"provider\":\"CLAUDE\",\"requestedModel\":\"synthetic-model\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestedModel").value("synthetic-model"));
        verifyNoInteractions(model);
    }

    @Test
    void modelConfigurationIsBoundToConsentAndRecordedWithoutInventingActualModel() {
        var first = new CuratorService(knowledge, model, new CuratorSettings("synthetic-a", ""));
        var second = new CuratorService(knowledge, model, new CuratorSettings("synthetic-b", ""));
        var preview = first.preview(workspace, source.card().id(), "CODEX");
        assertThat(preview.requestedModel()).isEqualTo("synthetic-a");
        assertThat(second.preview(workspace, source.card().id(), "CODEX").digest()).isNotEqualTo(preview.digest());
        assertThatThrownBy(() -> second.generate(workspace, source.card().id(), "CODEX", 1, preview.digest(), true))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
        verifyNoInteractions(model);
        var generated = first.generate(workspace, source.card().id(), "CODEX", 1, preview.digest(), true);
        assertThat(generated.version().markdown()).contains("요청 모델: synthetic-a", "실제 실행 모델: 미확인", preview.digest());
    }

    @Test
    void diagnosticsRequiresMutationHeaderAndRejectsUnknownProvider() throws Exception {
        mvc.perform(post("/api/ai/providers/CODEX/probe")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/providers/OTHER/probe").header("X-Forge-Client", "1")).andExpect(status().isBadRequest());
        verifyNoInteractions(model);
    }

    @Test
    void previewDoesNotCallModelAndOnlyIncludesSelectedQuotes() {
        var preview = curator.preview(workspace, source.card().id(), "CODEX");
        assertThat(preview.prompt()).contains("온도 21도", "E1").doesNotContain("private-name", "사람만의", workspace);
        verifyNoInteractions(model);
    }

    @Test
    void approvalAndExactProviderPayloadBindingAreRequired() {
        var preview = curator.preview(workspace, source.card().id(), "CODEX");
        assertThatThrownBy(() -> curator.generate(workspace, source.card().id(), "CODEX", 1, preview.digest(), false)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> curator.generate(workspace, source.card().id(), "CLAUDE", 1, preview.digest(), true)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> curator.generate(workspace, source.card().id(), "CODEX", 1, "wrong", true)).isInstanceOf(LibraryException.class);
        verifyNoInteractions(model);
    }

    @Test
    void createsSeparateAiDraftAndPreservesHumanDocumentAndSnapshots() {
        Document generated = generate(curator.preview(workspace, source.card().id(), "CODEX"));
        assertThat(generated.card().id()).isNotEqualTo(source.card().id());
        assertThat(generated.version().manuallyEdited()).isFalse();
        assertThat(generated.version().status()).isEqualTo("DRAFT");
        assertThat(generated.version().markdown()).contains("CODEX", "[E1]");
        assertThat(generated.version().evidence()).isEqualTo(source.version().evidence());
        assertThat(knowledge.get(workspace, source.card().id(), null)).isEqualTo(source);
        knowledge.delete(workspace, source.card().id());
        assertThat(knowledge.get(workspace, generated.card().id(), null).version().evidence()).hasSize(1);
        assertThat(knowledge.revise(workspace, generated.card().id(), 1, "검토", "사람 편집", null).version().manuallyEdited()).isTrue();
    }

    @Test
    void rejectsBadOutputsWithoutSavingAnything() {
        for (String output : List.of("not json", VALID + " {}", VALID.replace("E1", "E2"),
                VALID.replace("[\"E1\"]", "[]"), VALID.replace("관측", "x".repeat(121)),
                "{\"title\":\"\",\"claims\":[]}")) {
            when(model.generate(any(), anyString(), anyString())).thenReturn(output);
            assertThatThrownBy(() -> generate(curator.preview(workspace, source.card().id(), "CLAUDE"))).isInstanceOf(LibraryException.class);
            assertThat(knowledge.list(workspace)).hasSize(1);
        }
    }

    @Test
    void rechecksHumanEditsAfterInferenceWithoutHoldingDatabaseConnection() {
        when(model.generate(any(), anyString(), anyString())).thenAnswer(invocation -> {
            knowledge.revise(workspace, source.card().id(), 1, "수동 수정", "보존", null);
            return VALID;
        });
        assertThatThrownBy(() -> generate(curator.preview(workspace, source.card().id(), "CODEX"))).isInstanceOf(LibraryException.class);
        assertThat(knowledge.list(workspace)).hasSize(1);
        assertThat(knowledge.get(workspace, source.card().id(), null).version().markdown()).isEqualTo("보존");
    }

    @Test
    void rejectsDeletedEvidenceAndWorkspaceIsolationBeforeCallingModel() {
        String other = library.createWorkspace("다른 공간").id();
        assertThatThrownBy(() -> curator.preview(other, source.card().id(), "CODEX")).isInstanceOf(LibraryException.class);
        library.deleteEntry(workspace, entry);
        assertThatThrownBy(() -> curator.preview(workspace, source.card().id(), "CODEX")).isInstanceOf(LibraryException.class);
        verifyNoInteractions(model);
    }

    @Test
    void rejectsSourceDeletionDuringGenerationAndReleasesSlotAfterFailure() {
        when(model.generate(any(), anyString(), anyString())).thenThrow(new LibraryException(504, "timeout"));
        assertThatThrownBy(() -> generate(curator.preview(workspace, source.card().id(), "CODEX"))).isInstanceOf(LibraryException.class);
        doAnswer(invocation -> { library.deleteEntry(workspace, entry); return VALID; }).when(model).generate(any(), anyString(), anyString());
        assertThatThrownBy(() -> generate(curator.preview(workspace, source.card().id(), "CODEX"))).isInstanceOf(LibraryException.class);
        assertThat(knowledge.list(workspace)).hasSize(1);
        verify(model, times(2)).generate(any(), anyString(), anyString());
    }

    @Test
    void requiresMutationHeaderAndApprovalOverHttp() throws Exception {
        String path = "/api/workspaces/" + workspace + "/knowledge/" + source.card().id() + "/generation";
        mvc.perform(post(path + "/preview").contentType("application/json").content("{\"provider\":\"CODEX\"}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"provider\":\"CODEX\",\"expectedVersion\":1,\"digest\":\"none\",\"approved\":false}")).andExpect(status().isForbidden());
        verifyNoInteractions(model);
    }

    @Test
    void outdatedPreviewAndOversizedEvidenceAreRejectedBeforeTransmission() {
        var preview = curator.preview(workspace, source.card().id(), "CODEX");
        knowledge.revise(workspace, source.card().id(), 1, "새 제목", "새 편집", null);
        assertThatThrownBy(() -> generate(preview)).isInstanceOf(LibraryException.class);
        String large = library.upload(workspace, null, "large.txt", "가".repeat(17000).getBytes(StandardCharsets.UTF_8)).id();
        Document largeDraft = knowledge.create(workspace, "큰 근거", "검토", List.of(new Citation(large, 1, 1)));
        assertThatThrownBy(() -> curator.preview(workspace, largeDraft.card().id(), "CODEX"))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(413));
        verifyNoInteractions(model);
    }

    @Test
    void concurrentGenerationIsRejectedWithoutSecondModelCall() {
        var preview = curator.preview(workspace, source.card().id(), "CODEX");
        when(model.generate(any(), anyString(), anyString())).thenAnswer(invocation -> {
            assertThatThrownBy(() -> generate(preview)).isInstanceOfSatisfying(LibraryException.class,
                    error -> assertThat(error.status()).isEqualTo(409));
            return VALID;
        });
        generate(preview);
        verify(model, times(1)).generate(any(), anyString(), anyString());
    }
}
