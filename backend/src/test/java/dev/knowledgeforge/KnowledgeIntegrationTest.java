package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.KnowledgeService;
import dev.knowledgeforge.knowledge.domain.Knowledge.*;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.sources.application.SourceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class KnowledgeIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-knowledge-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
    }

    @Autowired KnowledgeService knowledge;
    @Autowired LibraryService library;
    @Autowired SourceService sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @TempDir Path fixture;
    String workspace;
    String entry;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("가상 지식").id();
        entry = library.upload(workspace, null, "notes.txt", "가상 관측\n온도 21도\n".getBytes(StandardCharsets.UTF_8)).id();
    }

    Document draft() {
        return knowledge.create(workspace, "관측 초안", "검토할 관측 메모", List.of(new Citation(entry, 1, 2)));
    }

    @Test
    void capturesServerSideEvidenceAndCreatesOnlyUnverifiedHumanDrafts() {
        Document document = draft();
        assertThat(document.version().status()).isEqualTo("DRAFT");
        assertThat(document.version().manuallyEdited()).isTrue();
        Evidence evidence = document.version().evidence().getFirst();
        assertThat(evidence.quote()).isEqualTo("가상 관측\n온도 21도");
        assertThat(evidence.freshness()).isEqualTo("CURRENT");
        assertThat(evidence.contentHash()).hasSize(64);
        assertThat(knowledge.snapshotContent(workspace, document.card().id(), evidence.snapshotId()))
                .isEqualTo(library.content(workspace, entry));
    }

    @Test
    void originalDeletionKeepsKnowledgeAndHistoricalBytes() {
        Document document = draft();
        byte[] bytes = library.content(workspace, entry);
        library.deleteEntry(workspace, entry);
        Document remaining = knowledge.get(workspace, document.card().id(), null);
        assertThat(remaining.version().evidence().getFirst().freshness()).isEqualTo("MISSING");
        assertThat(remaining.version().markdown()).isEqualTo(document.version().markdown());
        assertThat(knowledge.snapshotContent(workspace, document.card().id(), remaining.version().evidence().getFirst().snapshotId())).isEqualTo(bytes);
    }

    @Test
    void sourceEntryReplacementDoesNotBreakReferencesAndChangedTextBecomesStale() throws Exception {
        Files.writeString(fixture.resolve("source.txt"), "초기 관측");
        var source = sources.sync(workspace, sources.create(workspace, "가상 Source", "LOCAL_FOLDER", fixture.toString(), "실험").id());
        String originalEntry = library.entries(workspace, "ORIGINAL", source.rootEntryId()).getFirst().id();
        Document document = knowledge.create(workspace, "Source 지식", "초안", List.of(new Citation(originalEntry, 1, 1)));
        Files.writeString(fixture.resolve("other.txt"), "무관한 변경");
        sources.sync(workspace, source.id());
        assertThat(knowledge.get(workspace, document.card().id(), null).version().evidence().getFirst().freshness()).isEqualTo("CURRENT");
        assertThatThrownBy(() -> library.requireEntry(workspace, originalEntry)).isInstanceOf(LibraryException.class);
        Files.writeString(fixture.resolve("source.txt"), "새로운 관측");
        sources.sync(workspace, source.id());
        Evidence changed = knowledge.get(workspace, document.card().id(), null).version().evidence().getFirst();
        assertThat(changed.freshness()).isEqualTo("STALE");
        assertThat(changed.quote()).isEqualTo("초기 관측");
        assertThat(changed.sourceRevision()).isEqualTo(source.revision());
        assertThat(changed.sourceLocation()).isEqualTo(fixture.toString());
        assertThat(changed.sourceKind()).isEqualTo("LOCAL_FOLDER");
        sources.delete(workspace, source.id());
        assertThat(knowledge.get(workspace, document.card().id(), null).version().evidence().getFirst().freshness()).isEqualTo("MISSING");
    }

    @Test
    void editsAppendVersionsWithoutOverwritingAndRejectOutdatedEditors() {
        Document first = draft();
        String id = first.card().id();
        Document next = knowledge.revise(workspace, id, 1, "수정 제목", "사람 수정", null);
        assertThat(next.version().number()).isEqualTo(2);
        assertThat(knowledge.get(workspace, id, 1).version().markdown()).isEqualTo(first.version().markdown());
        assertThat(next.version().evidence().getFirst().snapshotId()).isEqualTo(first.version().evidence().getFirst().snapshotId());
        assertThatThrownBy(() -> knowledge.revise(workspace, id, 1, "덮어쓰기", "잘못된 수정", null))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
        assertThat(knowledge.get(workspace, id, null).version().markdown()).isEqualTo("사람 수정");
    }

    @Test
    void invalidEvidenceRollsBackCardVersionAndSnapshots() {
        assertThatThrownBy(() -> knowledge.create(workspace, "오류", "초안", List.of(
                new Citation(entry, 1, 1), new Citation(entry, 1, 999))))
                .isInstanceOf(LibraryException.class);
        assertThat(knowledge.list(workspace)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_snapshot", Integer.class)).isZero();
        Document first = draft();
        assertThatThrownBy(() -> knowledge.revise(workspace, first.card().id(), 1, "수정", "초안", List.of()))
                .isInstanceOf(LibraryException.class);
        assertThat(knowledge.get(workspace, first.card().id(), null).card().latestVersion()).isEqualTo(1);
    }

    @Test
    void rejectsMissingBinaryFolderAndInvalidDraftInputs() {
        String folder = library.createFolder(workspace, null, "폴더").id();
        String binary = library.upload(workspace, null, "unknown.bin", new byte[] {0, 1, (byte) 255}).id();
        for (String invalid : List.of(folder, binary, "missing")) {
            assertThatThrownBy(() -> knowledge.create(workspace, "초안", "본문", List.of(new Citation(invalid, 1, 1))))
                    .isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> knowledge.create(workspace, " ", "본문", List.of(new Citation(entry, 1, 1))))
                .isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> knowledge.create(workspace, "제목", "x".repeat(100_001), List.of(new Citation(entry, 1, 1))))
                .isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> knowledge.create(workspace, "제목", "본문", null)).isInstanceOf(LibraryException.class);
    }

    @Test
    void workspaceIsolationIncludesDraftCreationVersionsAndSnapshotDownloads() throws Exception {
        Document document = draft();
        String other = library.createWorkspace("다른 서재").id();
        String wrong = "/api/workspaces/" + other + "/knowledge/" + document.card().id();
        mvc.perform(get(wrong)).andExpect(status().isNotFound());
        mvc.perform(get(wrong + "/versions/1")).andExpect(status().isNotFound());
        mvc.perform(get(wrong + "/evidence/" + document.version().evidence().getFirst().snapshotId() + "/content"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(wrong).header("X-Forge-Client", "1")).andExpect(status().isNotFound());
        assertThatThrownBy(() -> knowledge.revise(other, document.card().id(), 1, "제목", "본문", null)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> knowledge.create(other, "제목", "본문", List.of(new Citation(entry, 1, 1)))).isInstanceOf(LibraryException.class);
    }

    @Test
    void knowledgeDeletionKeepsOriginalsAndCollectsOnlyUnreferencedSnapshots() {
        Document first = draft();
        Document second = draft();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_snapshot", Integer.class)).isEqualTo(1);
        knowledge.delete(workspace, first.card().id());
        assertThat(knowledge.snapshotContent(workspace, second.card().id(), second.version().evidence().getFirst().snapshotId())).isNotEmpty();
        knowledge.delete(workspace, second.card().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_snapshot", Integer.class)).isZero();
        assertThat(library.content(workspace, entry)).isNotEmpty();
    }

    @Test
    void deletingWorkspaceRemovesKnowledgeVersionsAndSnapshots() {
        draft();
        library.deleteWorkspace(workspace);
        for (String table : List.of("knowledge_card", "knowledge_version", "knowledge_evidence", "evidence_snapshot")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
    }

    @Test
    void restCreatesDraftRejectsConflictAndRequiresMutationHeader() throws Exception {
        String base = "/api/workspaces/" + workspace + "/knowledge";
        String body = """
                {"title":"가상 초안","markdown":"본문","citations":[{"entryId":"%s","startLine":1,"endLine":1}]}
                """.formatted(entry);
        mvc.perform(post(base).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(base).header("X-Forge-Client", "1").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version.status").value("DRAFT"))
                .andExpect(jsonPath("$.version.evidence[0].quote").value("가상 관측"));
        Document document = draft();
        mvc.perform(post(base + "/" + document.card().id() + "/versions").header("X-Forge-Client", "1")
                .contentType("application/json").content("{\"expectedVersion\":0,\"title\":\"제목\",\"markdown\":\"본문\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void simultaneousEditorsProduceOneNewVersionAndOneConflict() throws Exception {
        Document first = draft();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> edit = () -> {
                ready.countDown();
                release.await();
                try {
                    knowledge.revise(workspace, first.card().id(), 1, "수정", "동시 수정", null);
                    return 201;
                } catch (LibraryException exception) { return exception.status(); }
            };
            var firstEdit = executor.submit(edit);
            var secondEdit = executor.submit(edit);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(firstEdit.get(5, java.util.concurrent.TimeUnit.SECONDS), secondEdit.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(knowledge.get(workspace, first.card().id(), null).card().latestVersion()).isEqualTo(2);
    }

    @Test
    void explicitRecaptureCreatesNewEvidenceWhileRetainingPriorVersion() {
        Document first = draft();
        String other = library.upload(workspace, null, "new.txt", "새 근거".getBytes(StandardCharsets.UTF_8)).id();
        Document second = knowledge.revise(workspace, first.card().id(), 1, "새 초안", "새 내용", List.of(new Citation(other, 1, 1)));
        assertThat(second.version().evidence().getFirst().snapshotId()).isNotEqualTo(first.version().evidence().getFirst().snapshotId());
        assertThat(knowledge.get(workspace, first.card().id(), 1).version().evidence()).isEqualTo(first.version().evidence());
    }
}
