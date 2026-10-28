package dev.dougle;

import dev.dougle.knowledge.application.KnowledgeService;
import dev.dougle.knowledge.domain.Knowledge.*;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.sources.application.SourceService;
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
    void sourceIncrementalUpdatePreservesReferencesAndChangedTextBecomesStale() throws Exception {
        Files.writeString(fixture.resolve("source.txt"), "초기 관측");
        var source = sources.sync(workspace, sources.create(workspace, "가상 Source", "LOCAL_FOLDER", fixture.toString(), "실험").id());
        String originalEntry = library.entries(workspace, "ORIGINAL", source.rootEntryId()).getFirst().id();
        Document document = knowledge.create(workspace, "Source 지식", "초안", List.of(new Citation(originalEntry, 1, 1)));
        Files.writeString(fixture.resolve("other.txt"), "무관한 변경");
        sources.sync(workspace, source.id());
        assertThat(knowledge.get(workspace, document.card().id(), null).version().evidence().getFirst().freshness()).isEqualTo("CURRENT");
        assertThat(library.requireEntry(workspace, originalEntry).id()).isEqualTo(originalEntry);
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
    void locatesUploadedEvidenceWithRootOrderedAncestors() {
        Document root = draft();
        var rootLocation = knowledge.locateEvidence(workspace, root.card().id(), 1, root.version().evidence().getFirst().snapshotId());
        assertThat(rootLocation.entry().id()).isEqualTo(entry);
        assertThat(rootLocation.ancestors()).isEmpty();
        String parent = library.createFolder(workspace, null, "가상 폴더").id();
        String child = library.createFolder(workspace, parent, "하위 폴더").id();
        String nested = library.upload(workspace, child, "nested.txt", "가상 근거".getBytes(StandardCharsets.UTF_8)).id();
        Document document = knowledge.create(workspace, "중첩 근거", "내용 [E1]", List.of(new Citation(nested, 1, 1)));
        var location = knowledge.locateEvidence(workspace, document.card().id(), 1, document.version().evidence().getFirst().snapshotId());
        assertThat(location.entry().id()).isEqualTo(nested);
        assertThat(location.ancestors()).extracting(dev.dougle.library.domain.Entry::id).containsExactly(parent, child);
        assertThat(location.freshness()).isEqualTo("CURRENT");
    }

    @Test
    void navigationPreservesUnchangedSourceEntryAndReportsChangedContent() throws Exception {
        Files.writeString(fixture.resolve("source.txt"), "가상 첫 근거");
        var source = sources.sync(workspace, sources.create(workspace, "가상 위치", "LOCAL_FOLDER", fixture.toString(), "실험").id());
        String original = library.entries(workspace, "ORIGINAL", source.rootEntryId()).getFirst().id();
        Document document = knowledge.create(workspace, "Source 위치", "내용 [E1]", List.of(new Citation(original, 1, 1)));
        String snapshot = document.version().evidence().getFirst().snapshotId();
        Files.writeString(fixture.resolve("other.txt"), "다른 파일");
        sources.sync(workspace, source.id());
        var current = knowledge.locateEvidence(workspace, document.card().id(), 1, snapshot);
        assertThat(current.entry().id()).isEqualTo(original);
        assertThat(current.entry().name()).isEqualTo("source.txt");
        assertThat(current.freshness()).isEqualTo("CURRENT");
        Files.writeString(fixture.resolve("source.txt"), "가상 바뀐 근거");
        sources.sync(workspace, source.id());
        var changed = knowledge.locateEvidence(workspace, document.card().id(), 1, snapshot);
        assertThat(changed.freshness()).isEqualTo("STALE");
        assertThat(library.content(workspace, changed.entry().id())).isEqualTo("가상 바뀐 근거".getBytes(StandardCharsets.UTF_8));
        assertThat(knowledge.snapshotContent(workspace, document.card().id(), snapshot)).isEqualTo("가상 첫 근거".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deletedUploadDoesNotResolveToNewFileWithSameName() {
        Document document = draft();
        String snapshot = document.version().evidence().getFirst().snapshotId();
        library.deleteEntry(workspace, entry);
        library.upload(workspace, null, "notes.txt", "대체 파일".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, document.card().id(), 1, snapshot))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
        assertThat(knowledge.snapshotContent(workspace, document.card().id(), snapshot)).isNotEmpty();
    }

    @Test
    void renamedOrReregisteredSourceNeverAliasesOldEvidence() throws Exception {
        Files.writeString(fixture.resolve("old.txt"), "가상 원본");
        var source = sources.sync(workspace, sources.create(workspace, "가상 Source", "LOCAL_FOLDER", fixture.toString(), "실험").id());
        String original = library.entries(workspace, "ORIGINAL", source.rootEntryId()).getFirst().id();
        Document document = knowledge.create(workspace, "이름 변경", "내용 [E1]", List.of(new Citation(original, 1, 1)));
        String snapshot = document.version().evidence().getFirst().snapshotId();
        Files.move(fixture.resolve("old.txt"), fixture.resolve("new.txt"));
        sources.sync(workspace, source.id());
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, document.card().id(), 1, snapshot)).isInstanceOf(LibraryException.class);
        sources.delete(workspace, source.id());
        Files.move(fixture.resolve("new.txt"), fixture.resolve("old.txt"));
        sources.sync(workspace, sources.create(workspace, "새 Source", "LOCAL_FOLDER", fixture.toString(), "실험").id());
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, document.card().id(), 1, snapshot))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
    }

    @Test
    void navigationRequiresEvidenceMembershipInRequestedCardVersion() {
        Document first = draft();
        String other = library.upload(workspace, null, "other.txt", "다른 근거".getBytes(StandardCharsets.UTF_8)).id();
        Document second = knowledge.revise(workspace, first.card().id(), 1, "새 근거", "내용 [E1]", List.of(new Citation(other, 1, 1)));
        String snapshot = second.version().evidence().getFirst().snapshotId();
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, first.card().id(), 1, snapshot)).isInstanceOf(LibraryException.class);
        assertThat(knowledge.locateEvidence(workspace, first.card().id(), 2, snapshot).entry().id()).isEqualTo(other);
        Document unrelated = draft();
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, unrelated.card().id(), 1, snapshot)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> knowledge.locateEvidence(workspace, first.card().id(), 99, snapshot)).isInstanceOf(LibraryException.class);
    }

    @Test
    void locationRestIsReadOnlyAndWorkspaceScoped() throws Exception {
        Document document = draft();
        String suffix = "/knowledge/" + document.card().id() + "/versions/1/evidence/" + document.version().evidence().getFirst().snapshotId() + "/location";
        mvc.perform(get("/api/workspaces/" + workspace + suffix)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entry.id").value(entry)).andExpect(jsonPath("$.freshness").value("CURRENT"));
        String other = library.createWorkspace("다른 공간").id();
        mvc.perform(get("/api/workspaces/" + other + suffix)).andExpect(status().isNotFound());
        library.deleteEntry(workspace, entry);
        mvc.perform(get("/api/workspaces/" + workspace + suffix)).andExpect(status().isNotFound());
    }

    @Test
    void corruptFolderCycleFailsInsteadOfLooping() {
        String folder = library.createFolder(workspace, null, "가상 순환").id();
        String nested = library.upload(workspace, folder, "nested.txt", "가상 근거".getBytes(StandardCharsets.UTF_8)).id();
        Document document = knowledge.create(workspace, "순환 경로", "내용", List.of(new Citation(nested, 1, 1)));
        jdbc.update("UPDATE entry SET parent_id=? WHERE id=?", folder, folder);
        try {
            assertThatThrownBy(() -> knowledge.locateEvidence(workspace, document.card().id(), 1, document.version().evidence().getFirst().snapshotId()))
                    .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
        } finally { jdbc.update("UPDATE entry SET parent_id=NULL WHERE id=?", folder); }
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
