package dev.knowledgeforge;

import dev.knowledgeforge.attachment.application.AttachmentService;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.search.application.EvidenceAnswerPreview;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.EvidenceBundleValidator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuestionAttachmentIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-question-attachment-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired AttachmentService attachments;
    @Autowired LibraryService library;
    @Autowired EvidenceBundleService bundles;
    @Autowired EvidenceBundleValidator validator;
    @Autowired EvidenceAnswerPreview previews;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    String workspace;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("synthetic attachments").id();
    }
    byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(status));
    }
    byte[] image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @Test
    void attachmentOnlyQuestionIsReadyButDoesNotCreateLibraryItemsOrSendAi() throws Exception {
        var item = attachments.upload(workspace, "synthetic.txt", text("synthetic observation"));
        var bundle = bundles.create(workspace, "무엇이 보이나요", List.of(), false, List.of(item.id()));
        assertThat(bundle.payload().status()).isEqualTo("READY");
        assertThat(bundle.payload().items()).singleElement().satisfies(value -> {
            assertThat(value.kind()).isEqualTo("ATTACHMENT_TEXT");
            assertThat(value.quote()).contains("synthetic observation");
        });
        assertThat(validator.check(workspace, bundle.id()).eligible()).isTrue();
        for (String provider : List.of("CODEX", "CLAUDE")) {
            assertThat(previews.prepare(workspace, bundle.id(), provider, null).prompt()).contains("ATTACHMENT_TEXT", "untrusted", "synthetic observation");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry WHERE kind='FILE'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_job", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
        fails(409, () -> attachments.delete(workspace, item.id()));
        fails(409, () -> bundles.create(workspace, "다른 질문", List.of(), false, List.of(item.id())));
        bundles.delete(workspace, bundle.id());
        fails(404, () -> attachments.get(workspace, item.id()));
    }

    @Test
    void imageBytesAreBoundToCodexPreviewAndCannotSilentlyBecomeTextForClaude() throws Exception {
        var item = attachments.upload(workspace, "synthetic.png", image());
        var bundle = bundles.create(workspace, "이미지를 확인해 주세요", List.of(), false, List.of(item.id()));
        assertThat(previews.prepare(workspace, bundle.id(), "CODEX", null).prompt()).contains("ATTACHMENT_IMAGE");
        fails(400, () -> previews.prepare(workspace, bundle.id(), "CLAUDE", null));
        var images = bundles.images(workspace, bundle.id());
        assertThat(images).singleElement().satisfies(value -> {
            assertThat(value.label()).isEqualTo("R1");
            assertThat(value.content()).isEqualTo(attachments.get(workspace, item.id()).content());
        });
        jdbc.update("UPDATE question_attachment SET content=? WHERE id=?", text("tampered"), item.id());
        assertThat(validator.check(workspace, bundle.id()).eligible()).isFalse();
        fails(409, () -> previews.prepare(workspace, bundle.id(), "CODEX", null));
        fails(409, () -> bundles.images(workspace, bundle.id()));
    }

    @Test
    void rejectsCrossWorkspaceDuplicateAndOversizedSelectionsWithoutPartialBinding() {
        var item = attachments.upload(workspace, "note.txt", text("synthetic"));
        String other = library.createWorkspace("other").id();
        fails(404, () -> bundles.create(other, "question", List.of(), false, List.of(item.id())));
        fails(400, () -> bundles.create(workspace, "question", List.of(), false, List.of(item.id(), item.id())));
        fails(400, () -> bundles.create(workspace, "question", List.of(), false, List.of("a", "b", "c", "d", "e")));
        fails(404, () -> bundles.create(workspace, "question", List.of(), false, List.of(item.id(), "missing")));
        assertThat(attachments.get(workspace, item.id()).bundleId()).isNull();
        assertThat(bundles.list(workspace)).isEmpty();
        fails(413, () -> attachments.upload(workspace, "large.txt", new byte[AttachmentService.MAX_FILE_BYTES + 1]));
        fails(400, () -> attachments.upload(workspace, "empty.txt", new byte[0]));
        fails(400, () -> attachments.upload(workspace, "../note.txt", text("synthetic")));
        fails(415, () -> attachments.upload(workspace, "program.exe", text("synthetic")));
        fails(415, () -> attachments.upload(workspace, "broken.png", text("synthetic")));
    }

    @Test
    void expiryOnlyRemovesUnboundFilesAndWorkspaceDeletionCascades() {
        var pending = attachments.upload(workspace, "pending.txt", text("synthetic"));
        var bound = attachments.upload(workspace, "bound.txt", text("synthetic"));
        var bundle = bundles.create(workspace, "question", List.of(), false, List.of(bound.id()));
        jdbc.update("UPDATE question_attachment SET created_at='2000-01-01T00:00:00Z'");
        attachments.expire();
        fails(404, () -> attachments.get(workspace, pending.id()));
        assertThat(attachments.get(workspace, bound.id()).bundleId()).isEqualTo(bundle.id());
        library.deleteWorkspace(workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_attachment", Integer.class)).isZero();
    }

    @Test
    void truncationIsVisibleAndExtractedTextTamperingBlocksApproval() {
        var item = attachments.upload(workspace, "long.txt", text("synthetic ".repeat(1000)));
        assertThat(item.truncated()).isTrue();
        assertThat(item.characters()).isEqualTo(3000);
        var bundle = bundles.create(workspace, "question", List.of(), false, List.of(item.id()));
        assertThat(previews.prepare(workspace, bundle.id(), "CODEX", null).prompt()).contains("\"truncated\":true");
        jdbc.update("UPDATE question_attachment SET extracted_text='tampered' WHERE id=?", item.id());
        fails(409, () -> previews.prepare(workspace, bundle.id(), "CODEX", null));
    }

    @Test
    void multipartApiReturnsOnlyMetadataAndHonorsWorkspaceIsolation() throws Exception {
        mvc.perform(multipart("/api/workspaces/{id}/attachments", workspace)
                .header("X-Forge-Client", "1")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", text("synthetic-private-text"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("name").value("note.txt"))
                .andExpect(jsonPath("content").doesNotExist()).andExpect(jsonPath("text").doesNotExist());
        mvc.perform(multipart("/api/workspaces/missing/attachments")
                .header("X-Forge-Client", "1")
                .file(new MockMultipartFile("file", "note.txt", "text/plain", text("synthetic"))))
                .andExpect(status().isNotFound());
        var item = attachments.upload(workspace, "delete.txt", text("synthetic"));
        mvc.perform(delete("/api/workspaces/{workspace}/attachments/{id}", workspace, item.id()).header("X-Forge-Client", "1")).andExpect(status().isNoContent());
        fails(404, () -> attachments.get(workspace, item.id()));
    }
}
