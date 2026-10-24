package dev.knowledgeforge;

import com.jayway.jsonpath.JsonPath;
import dev.knowledgeforge.library.application.LibraryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LibraryIntegrationTest {
    private static final Path DATA = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LibraryService service;

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("forge-test-");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workspace");
    }

    private String workspace(String name) throws Exception {
        return id(mvc.perform(post("/api/workspaces").header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private String folder(String workspaceId, String parentId, String name) throws Exception {
        String content = "{\"name\":\"" + name + "\",\"parentId\":"
                + (parentId == null ? "null" : "\"" + parentId + "\"") + "}";
        return id(mvc.perform(post("/api/workspaces/" + workspaceId + "/folders")
                .header("X-Forge-Client", "1").contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isCreated()).andReturn());
    }

    private String upload(String workspaceId, String parentId, String name, byte[] bytes) throws Exception {
        var builder = multipart("/api/workspaces/" + workspaceId + "/files")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        if (parentId != null) builder.param("parentId", parentId);
        return id(mvc.perform(builder.header("X-Forge-Client", "1"))
                .andExpect(status().isCreated()).andReturn());
    }

    private String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");
    }

    @Test
    void workspaceLifecyclePersistsActiveAndCascades() throws Exception {
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.workspaces").isEmpty())
                .andExpect(jsonPath("$.activeWorkspaceId").isEmpty());
        String first = workspace("연구");
        String second = workspace("개인");
        folder(first, null, "문서");
        mvc.perform(put("/api/workspaces/" + first + "/active").header("X-Forge-Client", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.activeWorkspaceId").value(first));
        mvc.perform(delete("/api/workspaces/" + first).header("X-Forge-Client", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.activeWorkspaceId").value(second));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry", Integer.class)).isZero();
        mvc.perform(delete("/api/workspaces/" + second).header("X-Forge-Client", "1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.activeWorkspaceId").isEmpty());
    }

    @Test
    void nestedFoldersAreNavigableAndKnowledgeRemainsIndependent() throws Exception {
        String workspaceId = workspace("실험");
        String root = folder(workspaceId, null, "참고");
        String nested = folder(workspaceId, root, "노트");
        String file = upload(workspaceId, nested, "sample.md", "# 가상 자료".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries"))
                .andExpect(jsonPath("$[0].id").value(root)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries").param("parentId", root))
                .andExpect(jsonPath("$[0].id").value(nested));
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries").param("parentId", nested))
                .andExpect(jsonPath("$[0].id").value(file));
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries").param("area", "KNOWLEDGE"))
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries")
                        .param("area", "KNOWLEDGE").param("parentId", root)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/workspaces/" + workspaceId + "/entries/" + root)
                        .header("X-Forge-Client", "1")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry", Integer.class)).isZero();
    }

    @Test
    void originalsRoundTripAndTextIsNotRenderedAsHtml() throws Exception {
        String workspaceId = workspace("문서");
        byte[] bytes = "<script>alert('가상')</script>".getBytes(StandardCharsets.UTF_8);
        String file = upload(workspaceId, null, "안내.html", bytes);
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries/" + file + "/content"))
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")));
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries/" + file + "/preview"))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.text").value(new String(bytes, StandardCharsets.UTF_8)));
    }

    @Test
    void binaryAndLargeFilesHaveExplicitPreviewLimits() throws Exception {
        String workspaceId = workspace("미리보기");
        String binary = upload(workspaceId, null, "binary.bin", new byte[] {0, 1, 2, (byte) 255});
        String large = upload(workspaceId, null, "large.txt", new byte[1024 * 1024 + 1]);
        for (String file : new String[] {binary, large}) {
            mvc.perform(get("/api/workspaces/" + workspaceId + "/entries/" + file + "/preview"))
                    .andExpect(jsonPath("$.supported").value(false)).andExpect(jsonPath("$.reason").isNotEmpty());
        }
    }

    @Test
    void workspaceCannotReadDeleteOrAttachAnotherWorkspacesEntries() throws Exception {
        String first = workspace("첫째");
        String second = workspace("둘째");
        String parent = folder(first, null, "소유 폴더");
        String file = upload(first, null, "secret.txt", "synthetic".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/workspaces/" + second + "/entries/" + file + "/content")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/workspaces/" + second + "/entries/" + file)
                        .header("X-Forge-Client", "1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/workspaces/" + second + "/folders").header("X-Forge-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"parentId\":\"" + parent + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateWorkspaceAndSiblingNamesReturnConflictWithoutOverwriting() throws Exception {
        String workspaceId = workspace("Duplicate");
        for (String duplicate : new String[]{" Duplicate ", "DUPLICATE", "duplicate"}) {
            mvc.perform(post("/api/workspaces").header("X-Forge-Client", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + duplicate + "\"}"))
                    .andExpect(status().isConflict());
        }
        assertThat(service.session().workspaces()).hasSize(1);
        assertThat(service.session().activeWorkspaceId()).isEqualTo(workspaceId);
        mvc.perform(post("/api/workspaces").header("X-Forge-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"duplicate\"}"))
                .andExpect(status().isConflict());
        String parent = folder(workspaceId, null, "notes");
        mvc.perform(post("/api/workspaces/" + workspaceId + "/folders").header("X-Forge-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"notes\"}"))
                .andExpect(status().isConflict());
        folder(workspaceId, parent, "notes");
        assertThat(service.entries(workspaceId, "ORIGINAL", null)).hasSize(1);
    }

    @Test
    void rejectsInvalidNamesAndMissingWorkspaces() throws Exception {
        for (String name : new String[] {"", " ", "..", "../escape", "a/b"}) {
            mvc.perform(post("/api/workspaces").header("X-Forge-Client", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/workspaces/missing/entries")).andExpect(status().isNotFound());
        mvc.perform(put("/api/workspaces/missing/active").header("X-Forge-Client", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileCannotBeUsedAsFolderAndInvalidAreaIsRejected() throws Exception {
        String workspaceId = workspace("타입");
        String file = upload(workspaceId, null, "empty.txt", new byte[0]);
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries").param("parentId", file))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries").param("area", "OTHER"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries/" + file + "/preview"))
                .andExpect(jsonPath("$.supported").value(true)).andExpect(jsonPath("$.text").value(""));
    }

    @Test
    void oversizedUploadDoesNotLeaveAnEntry() throws Exception {
        String workspaceId = workspace("제한");
        mvc.perform(multipart("/api/workspaces/" + workspaceId + "/files")
                        .file(new MockMultipartFile("file", "too-big.bin", "application/octet-stream",
                                new byte[10 * 1024 * 1024 + 1])).header("X-Forge-Client", "1"))
                .andExpect(status().isPayloadTooLarge());
        assertThat(service.entries(workspaceId, "ORIGINAL", null)).isEmpty();
    }

    @Test
    void blocksForeignOriginsRebindingAndUnmarkedWrites() throws Exception {
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bad\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/session").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/session").with(request -> { request.setServerName("untrusted.example"); return request; }))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspaces").header("Origin", "null").header("X-Forge-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"bad\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingInactiveWorkspaceDoesNotChangeSelection() throws Exception {
        String first = workspace("비활성");
        String active = workspace("활성");
        mvc.perform(delete("/api/workspaces/" + first).header("X-Forge-Client", "1"))
                .andExpect(status().isNoContent());
        assertThat(service.session().activeWorkspaceId()).isEqualTo(active);
    }
}
