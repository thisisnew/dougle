package dev.dougle;

import com.jayway.jsonpath.JsonPath;
import dev.dougle.library.application.LibraryService;
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
    @Test
    void partialExtractionIsVisibleInMaterialWarnings() throws Exception {
        String workspaceId = workspace("부분 추출 자료");
        String entryId = upload(workspaceId, null, "note.txt", "Synthetic notes".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE extracted_document SET status='PARTIAL',error='텍스트가 길어 앞부분만 검색합니다.' WHERE entry_id=?", entryId);
        mvc.perform(get("/api/workspaces/" + workspaceId + "/analysis-failures"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].entryId").value(entryId))
                .andExpect(jsonPath("$[0].error").value("텍스트가 길어 앞부분만 검색합니다."));
    }

    @Test
    void uploadAboveTenMiBStoresAndIndexesTheLastParagraph() throws Exception {
        String workspaceId = workspace("큰 가상 문서");
        byte[] content = ("Synthetic greenhouse observation.\n".repeat(400_000)
                + "\nFINALUPLOADMARKER").getBytes(StandardCharsets.UTF_8);
        assertThat(content.length).isGreaterThan(10 * 1024 * 1024);
        String entryId = upload(workspaceId, null, "large.txt", content);
        assertThat(jdbc.queryForObject("SELECT length(content) FROM entry WHERE id=?", Long.class, entryId))
                .isEqualTo((long) content.length);
        assertThat(jdbc.queryForObject("SELECT text FROM extracted_document WHERE entry_id=?", String.class, entryId))
                .hasSizeLessThanOrEqualTo(64 * 1024);
        var parts = dev.dougle.ingestion.adapter.JdbcTextParts.document(jdbc, entryId);
        assertThat(parts.stream().mapToLong(String::length).sum()).isGreaterThan(13_000_000);
        assertThat(parts.getLast().stripTrailing()).endsWith("FINALUPLOADMARKER");
        mvc.perform(get("/api/workspaces/" + workspaceId + "/search").param("query", "FINALUPLOADMARKER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].entryId").value(entryId))
                .andExpect(jsonPath("$[0].snippet", org.hamcrest.Matchers.containsString("FINALUPLOADMARKER")));
    }

    @Test
    void analysisFailureMetadataIncludesAncestorsAndClearsAfterSync() throws Exception {
        String workspaceId = workspace("오류 자료 검사");
        String parent = folder(workspaceId, null, "상위");
        String child = folder(workspaceId, parent, "하위");
        String entry = upload(workspaceId, child, "note.txt", "가상 정상 자료".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE extracted_document SET status='ERROR', error='가상 오류' WHERE entry_id=?", entry);
        mvc.perform(get("/api/workspaces/" + workspaceId + "/analysis-failures"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].entryId", org.hamcrest.Matchers.containsInAnyOrder(parent, child, entry)));
        String other = workspace("다른 오류 검사");
        mvc.perform(get("/api/workspaces/" + other + "/analysis-failures"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/workspaces/" + workspaceId + "/files/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/workspaces/" + workspaceId + "/analysis-failures"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/workspaces/missing/analysis-failures")).andExpect(status().isNotFound());
    }

    @Test
    void uploadedPdfCanBePreviewedAndUsedForNaturalQuestion() throws Exception {
        String workspaceId = workspace("가상 PDF 연구");
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var output = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Synthetic greenhouse corrections: monthly average 12 cases.");
                content.endText();
            }
            pdf.save(output);
            bytes = output.toByteArray();
        }
        String entryId = upload(workspaceId, null, "synthetic.pdf", bytes);
        mvc.perform(get("/api/workspaces/" + workspaceId + "/entries/" + entryId + "/preview"))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("12 cases")));
        mvc.perform(post("/api/workspaces/" + workspaceId + "/evidence-bundles").header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"How many greenhouse corrections happen each month?\",\"domains\":[],\"includeKnowledge\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.payload.items[0].quote").value(org.hamcrest.Matchers.containsString("12 cases")));
    }

    @Test
    void fileSyncRebuildsNestedUploadAnalysisAndKeepsWorkspaceIsolation() throws Exception {
        String workspaceId = workspace("가상 동기화");
        String nested = folder(workspaceId, null, "기록");
        String entryId = upload(workspaceId, nested, "note.txt", "가상 관측 기록".getBytes(StandardCharsets.UTF_8));
        String other = workspace("다른 서재");
        String otherEntry = upload(other, null, "other.txt", "다른 관측 기록".getBytes(StandardCharsets.UTF_8));
        jdbc.update("DELETE FROM extracted_document WHERE entry_id IN (?,?)", entryId, otherEntry);
        mvc.perform(post("/api/workspaces/" + workspaceId + "/files/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.failures").isEmpty());
        assertThat(service.analysis(workspaceId, entryId).status()).isEqualTo("READY");
        assertThat(service.analysis(other, otherEntry).status()).isEqualTo("PENDING");
    }

    private static final Path DATA = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired LibraryService service;
    @Autowired dev.dougle.sources.application.SourceService sources;

    @Test
    void fileSyncReportsUnreadableUploadsAndDoesNotReprocessManagedSources() throws Exception {
        String workspaceId = workspace("가상 혼합 자료");
        var folder = Files.createTempDirectory(DATA, "linked-");
        Files.writeString(folder.resolve("linked.txt"), "가상 연결 자료");
        var source = sources.create(workspaceId, "연결 폴더", "LOCAL_FOLDER", folder.toString(), "");
        sources.sync(workspaceId, source.id());
        upload(workspaceId, null, "empty.txt", new byte[0]);
        upload(workspaceId, null, "ready.txt", "가상 정상 자료".getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/workspaces/" + workspaceId + "/files/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(2))
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].name").value("empty.txt"));
        mvc.perform(post("/api/workspaces/missing/files/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/workspaces/" + workspaceId + "/files/sync"))
                .andExpect(status().isForbidden());
    }

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
    void workspaceNameAllowsTwentyCharactersButRejectsTwentyOne() throws Exception {
        workspace("가".repeat(20));
        mvc.perform(post("/api/workspaces").header("X-Forge-Client", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + "가".repeat(21) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(service.session().workspaces()).hasSize(1);
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
                                new byte[0]) {
                            @Override public long getSize() { return dev.dougle.sources.adapter.CollectionLimits.MAX_DOCUMENT + 1; }
                        }).header("X-Forge-Client", "1"))
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
