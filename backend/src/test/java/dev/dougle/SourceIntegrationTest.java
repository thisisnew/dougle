package dev.dougle;

import dev.dougle.ingestion.application.DocumentAnalyzer;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.application.SourceService;
import dev.dougle.sources.application.SourceStore;
import dev.dougle.sources.application.Collector;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.ingestion.domain.Analysis;
import dev.dougle.sources.domain.Source;
import java.net.URI;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SourceIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-phase2-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
    }

    @TempDir Path fixture;

    @Autowired dev.dougle.conversation.application.ChatThreadService threads;
    @Autowired dev.dougle.search.application.EvidenceBundleValidator evidenceValidator;

    @Test
    void streamedFolderTailBecomesValidatedEvidenceAndRefreshInvalidatesIt() throws Exception {
        String workspace = library.createWorkspace("streamed workflow").id();
        Path file = fixture.resolve("large-note.txt");
        Files.writeString(file, "Synthetic observations.\n".repeat(150_000) + "ZEPHYR final procedure: inspect the amber valve.");
        Source source = local(workspace, "streamed notes", "");
        assertThat(sources.sync(workspace, source.id(), search::refresh).status()).isEqualTo("READY");
        var bundle = threads.create(workspace, new dev.dougle.conversation.application.ChatThreadService.Create(
                "stream-tail-question", null, null, "ZEPHYR", List.of()));
        assertThat(bundle.payload().items()).hasSize(1);
        assertThat(bundle.payload().items().getFirst().quote()).contains("ZEPHYR final procedure");
        assertThat(evidenceValidator.check(workspace, bundle.id()).eligible()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extracted_text_part", Integer.class)).isGreaterThan(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_analysis_text_part WHERE source_id=?", Integer.class, source.id())).isZero();
        Files.writeString(file, "Replacement synthetic content without the previous procedure.");
        assertThat(sources.sync(workspace, source.id(), search::refresh).status()).isEqualTo("READY");
        assertThat(evidenceValidator.check(workspace, bundle.id()).eligible()).isFalse();
        assertThat(search.searchQuestion(workspace, "ZEPHYR", List.of(), false)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extracted_text_part", Integer.class)).isZero();
    }

    @Test
    void searchableTailAfterBlankLargePrefixKeepsFailedSiblingAsPartial() throws Exception {
        String workspace = library.createWorkspace("빈 앞부분과 뒤쪽 근거").id();
        Files.writeString(fixture.resolve("broken.txt"), "synthetic unreadable sibling");
        Files.writeString(fixture.resolve("large.txt"), "synthetic streamed fixture");
        String blank = " ".repeat(64 * 1024);
        var parts = new java.util.ArrayList<String>(java.util.Collections.nCopies(32, blank));
        parts.add("FINAL_SYNTHETIC_EVIDENCE");
        doReturn(new Analysis(blank, "text/plain", "READY", null, List.of(), parts))
                .when(analyzer).analyzeStream(eq("large.txt"), any(java.io.InputStream.class));
        doReturn(new Analysis("", "text/plain", "ERROR", "synthetic unreadable sibling", List.of()))
                .when(analyzer).analyzeStream(eq("broken.txt"), any(java.io.InputStream.class));
        Source source = local(workspace, "뒤쪽 근거", "");
        var synchronizedSource = sources.sync(workspace, source.id());
        assertThat(synchronizedSource.status()).isEqualTo("PARTIAL");
        assertThat(synchronizedSource.error()).contains("broken.txt").doesNotContain("검색 가능한 자료가 없습니다");
        String entry = entryId(source.id(), "large.txt");
        assertThat(jdbc.queryForObject("SELECT length(trim(text)) FROM extracted_document WHERE entry_id=?", Integer.class, entry)).isZero();
        assertThat(jdbc.queryForObject("SELECT text FROM extracted_text_part WHERE entry_id=? ORDER BY ordinal DESC LIMIT 1", String.class, entry))
                .isEqualTo("FINAL_SYNTHETIC_EVIDENCE");
    }

    @Test
    void linkRegistrationUsesAddressKindWithoutCollecting() {
        String workspace = library.createWorkspace("링크 분류").id();
        for (String selected : List.of("URL", "GITHUB")) {
            var repository = sources.create(workspace, "저장소 " + selected, selected, " https://github.com/example/repo.git/ ", "");
            assertThat(repository.kind()).isEqualTo("GITHUB");
            for (String address : List.of("https://example.org/page")) {
                assertThat(sources.create(workspace, "페이지 " + selected + " " + address.hashCode(), selected, address, "").kind()).isEqualTo("URL");
            }
        }
        verifyNoInteractions(remote);
    }

    @Autowired SourceService sources;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @MockitoSpyBean DocumentAnalyzer analyzer;
    @Autowired SourceStore sourceStore;
    @Autowired LibraryStore libraryStore;
    @Autowired List<Collector> collectors;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean RemoteReader remote;
    @Autowired dev.dougle.knowledge.application.KnowledgeService knowledge;
    @Autowired dev.dougle.knowledge.application.PublicationService publications;
    @Autowired dev.dougle.search.application.EvidenceBundleService bundles;
    @Autowired dev.dougle.search.application.EvidenceBundleValidator bundleValidator;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
    }

    @Test
    void cancelledCollectionResumesCompletedAnalysisFromDatabaseCheckpoint() throws Exception {
        String workspace = library.createWorkspace("체크포인트 가상 자료").id();
        Files.writeString(fixture.resolve("a.txt"), "Synthetic first observation");
        Files.writeString(fixture.resolve("b.txt"), "Synthetic second observation");
        Source source = local(workspace, "가상 폴더", "");
        doAnswer(invocation -> {
            Analysis result = (Analysis) invocation.callRealMethod();
            assertThat(sources.progress(workspace, source.id()).stage()).isEqualTo("EXTRACTING");
            sources.cancel(workspace, source.id());
            return result;
        }).when(analyzer).analyzeStream(eq("a.txt"), any(java.io.InputStream.class));
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_analysis_checkpoint WHERE source_id=?", Integer.class, source.id())).isEqualTo(1);
        assertThat(library.entries(workspace, "ORIGINAL", null)).isEmpty();
        doCallRealMethod().when(analyzer).analyzeStream(eq("a.txt"), any(java.io.InputStream.class));
        clearInvocations(analyzer);
        var restarted = new SourceService(sourceStore, libraryStore, collectors, analyzer);
        assertThat(restarted.sync(workspace, source.id()).status()).isEqualTo("READY");
        verify(analyzer, never()).analyzeStream(eq("a.txt"), any(java.io.InputStream.class));
        verify(analyzer).analyzeStream(eq("b.txt"), any(java.io.InputStream.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_analysis_checkpoint WHERE source_id=?", Integer.class, source.id())).isZero();
        String other = library.createWorkspace("격리된 진행 상태").id();
        mvc.perform(get("/api/workspaces/" + other + "/sources/" + source.id() + "/progress")).andExpect(status().isNotFound());
    }

    @Test
    void unchangedUnreadableFileIsNotAnalyzedForeverAndDoesNotDiscardHealthySibling() throws Exception {
        String workspace = library.createWorkspace("부분 실패 가상 자료").id();
        Files.writeString(fixture.resolve("broken.txt"), "synthetic broken bytes");
        Files.writeString(fixture.resolve("healthy.txt"), "healthy evidence");
        Source source = local(workspace, "부분 실패", "");
        doReturn(new Analysis("", "text/plain", "ERROR", "파일 손상을 확인하세요.", List.of()))
                .when(analyzer).analyzeStream(eq("broken.txt"), any(java.io.InputStream.class));
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("PARTIAL");
        assertThat(entryId(source.id(), "healthy.txt")).isNotBlank();
        clearInvocations(analyzer);
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("PARTIAL");
        verifyNoInteractions(analyzer);
        doCallRealMethod().when(analyzer).analyzeStream(eq("broken.txt"), any(java.io.InputStream.class));
        Files.writeString(fixture.resolve("broken.txt"), "repaired synthetic evidence");
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("READY");
        verify(analyzer).analyzeStream(eq("broken.txt"), any(java.io.InputStream.class));
        verify(analyzer, never()).analyzeStream(eq("healthy.txt"), any(java.io.InputStream.class));
    }

    @Test
    void registrationCollectsFolderAndIndexesBeforeReturning() throws Exception {
        String workspace = library.createWorkspace("첫 동기화").id();
        Files.writeString(fixture.resolve("notes.txt"), "가상 온실 관측 기록");
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                        .contentType("application/json").content("{\"name\":\"가상 폴더\",\"kind\":\"LOCAL_FOLDER\",\"location\":\"" + fixture + "\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.syncedAt").isNotEmpty()).andExpect(jsonPath("$.rootEntryId").isNotEmpty());
        assertThat(search.search(workspace, "온실", List.of())).isNotEmpty();
    }

    @Test void archivedRemoteMaterialsRemainSearchableAfterDeniedSync() throws Exception {
        Files.writeString(fixture.resolve("archived.txt"), "archived unique telescope evidence");
        for (String kind : List.of("GITHUB")) {
            String workspace = library.createWorkspace("보관 자료 " + kind).id();
            Source original = sources.create(workspace, "보관 자료", "LOCAL_FOLDER", fixture.toString(), "");
            Source collected = sources.sync(workspace, original.id(), search::refresh);
            jdbc.update("UPDATE source SET kind=? WHERE id=?", kind, original.id());
            mvc.perform(post("/api/workspaces/" + workspace + "/sources/" + original.id() + "/sync").header("X-Forge-Client", "1"))
                    .andExpect(status().isBadRequest());
            assertThat(sourceStore.find(workspace, original.id()).orElseThrow().rootEntryId()).isEqualTo(collected.rootEntryId());
            assertThat(search.search(workspace, "telescope", List.of())).isNotEmpty();
        }
        verifyNoInteractions(remote);
    }

    @Test
    void notionUrlRegistrationRequiresConnectionWithoutCollectingAppShell() throws Exception {
        String workspace = library.createWorkspace("플러그인 안내").id();
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                        .contentType("application/json").content("{\"name\":\"가상 페이지\",\"kind\":\"URL\",\"location\":\"https://app.notion.com/p/synthetic\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ERROR"));
        assertThat(sources.list(workspace)).hasSize(1);
        verifyNoInteractions(remote);
        assertThat(search.search(workspace, "Notion", List.of())).isEmpty();
    }

    @Test
    void urlRegistrationCollectsAndIndexesWebDocument() throws Exception {
        String workspace = library.createWorkspace("첫 웹 동기화").id();
        URI uri = URI.create("https://example.org/automatic");
        when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, "text/plain", "가상 온실 기록".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                        .contentType("application/json").content("{\"name\":\"가상 링크\",\"kind\":\"URL\",\"location\":\"" + uri + "\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(search.search(workspace, "온실", List.of())).isNotEmpty();
        verify(remote).read(uri);
    }

    @Test void githubWebRegistrationIsRejectedBeforeCollection() throws Exception {
        String workspace = library.createWorkspace("GitHub 차단").id();
        for (String address : List.of("https://github.com/example/repo", "https://github.com/example/repo/blob/main/file", "https://api.github.com/repos/example/repo", "https://raw.githubusercontent.com/example/repo/main/file")) {
            mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                    .contentType("application/json").content("{\"name\":\"코드\",\"kind\":\"URL\",\"location\":\"" + address + "\",\"sync\":true}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(sources.list(workspace)).isEmpty();
        verifyNoInteractions(remote);
    }

    @Test void confluenceRegistrationExplainsMissingConnectionWithoutIndexingLoginPage() throws Exception {
        String workspace = library.createWorkspace("Confluence 연결 안내").id();
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                .contentType("application/json").content("{\"name\":\"팀 문서\",\"kind\":\"URL\",\"location\":\"https://synthetic.atlassian.net/wiki/spaces/TEAM/pages/123\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("연결이 필요")));
        assertThat(search.search(workspace, "팀 문서", List.of())).isEmpty();
        verifyNoInteractions(remote);
    }

    @Test void folderWithSymlinkStillIndexesHealthyFilesAndKeepsOriginalUntouched() throws Exception {
        String workspace = library.createWorkspace("혼합 코드 폴더").id();
        Files.writeString(fixture.resolve("notes.txt"), "SYNTHETIC_HEALTHY_TELESCOPE");
        Files.createSymbolicLink(fixture.resolve("cycle"), fixture);
        Source source = sources.create(workspace, "코드", "LOCAL_FOLDER", fixture.toString(), "");
        Source result = sources.sync(workspace, source.id(), search::refresh);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.error()).contains("cycle").contains("심볼릭");
        assertThat(search.search(workspace, "SYNTHETIC_HEALTHY_TELESCOPE", List.of())).isNotEmpty();
        assertThat(Files.isSymbolicLink(fixture.resolve("cycle"))).isTrue();
    }

    @Test
    void registrationRejectsGithubEvenWithAvailableCollector() {
        String workspace = library.createWorkspace("첫 저장소 동기화").id();
        Collector collector = mock(Collector.class);
        when(collector.kind()).thenReturn("GITHUB");
        try {
            when(collector.collect(anyString())).thenReturn(new dev.dougle.sources.domain.CollectedSnapshot("a".repeat(40),
                    List.of(new dev.dougle.sources.domain.CollectedSnapshot.File("notes.txt", "가상 온실 기록".getBytes(StandardCharsets.UTF_8)))));
        } catch (Exception exception) { throw new AssertionError(exception); }
        var controller = new dev.dougle.sources.adapter.SourceController(new SourceService(sourceStore, libraryStore, List.of(collector), analyzer), search);
        assertThatThrownBy(() -> controller.create(workspace, new dev.dougle.sources.adapter.SourceController.Registration(
                "가상 저장소", "GITHUB", "https://github.com/example/synthetic", "", true))).isInstanceOf(LibraryException.class);
        verifyNoInteractions(collector);
        assertThat(search.search(workspace, "온실", List.of())).isEmpty();
    }

    @Test
    void firstSyncFailureKeepsRegisteredSourceWithActionableLimit() throws Exception {
        String workspace = library.createWorkspace("가상 용량 오류").id();
        try (var file = new java.io.RandomAccessFile(fixture.resolve("large.txt").toFile(), "rw")) {
            file.setLength(dev.dougle.sources.adapter.CollectionLimits.MAX_DOCUMENT + 1);
        }
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1")
                        .contentType("application/json").content("{\"name\":\"가상 큰 폴더\",\"kind\":\"LOCAL_FOLDER\",\"location\":\"" + fixture + "\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.syncedAt").isEmpty())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("256 MiB")));
        assertThat(sources.list(workspace)).hasSize(1);
    }

    @Test
    void workspacePollingToggleIsAtomicAndInheritedByNewSources() throws Exception {
        String workspace = library.createWorkspace("자동 갱신 가상 테스트").id();
        String other = library.createWorkspace("분리된 테스트").id();
        var first = sources.create(workspace, "첫 자료", "URL", "https://example.com/one", "");
        var isolated = sources.create(other, "별도 자료", "URL", "https://example.com/other", "");
        assertThat(sources.workspacePolling(workspace).enabled()).isFalse();
        var enabled = sources.configureWorkspacePolling(workspace, true, 0);
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.revision()).isEqualTo(1);
        assertThat(enabled.mixed()).isFalse();
        assertThat(sources.list(workspace).getFirst().pollingEnabled()).isTrue();
        assertThat(sources.list(workspace).getFirst().nextPollAt()).isNotNull();
        var added = sources.create(workspace, "나중 자료", "URL", "https://example.com/two", "");
        assertThat(added.pollingEnabled()).isTrue();
        assertThat(added.nextPollAt()).isNotNull();
        assertThat(sourceStore.find(other, isolated.id()).orElseThrow().pollingEnabled()).isFalse();
        assertThatThrownBy(() -> sources.configureWorkspacePolling(workspace, false, 0)).hasMessageContaining("설정이 변경");
        assertThat(sources.list(workspace)).allMatch(source -> source.pollingEnabled());
        sources.configureWorkspacePolling(workspace, false, 1);
        assertThat(sources.list(workspace)).allMatch(source -> !source.pollingEnabled() && source.nextPollAt() == null);
        assertThat(sourceStore.find(workspace, first.id()).orElseThrow().pollingRevision()).isEqualTo(2);
        assertThat(sources.create(workspace, "끄고 추가", "URL", "https://example.com/off", "").pollingEnabled()).isFalse();
        assertThatThrownBy(() -> sources.configureWorkspacePolling(workspace, null, 2)).hasMessageContaining("설정");
        assertThatThrownBy(() -> sources.configureWorkspacePolling("missing", true, 0)).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        library.deleteWorkspace(workspace);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_source_polling WHERE workspace_id=?", Integer.class, workspace)).isZero();
    }

    @Test
    void legacyIndividualPollingIsPreservedUntilWorkspaceToggleIsUsed() {
        String workspace = library.createWorkspace("기존 갱신 설정").id();
        var first = sources.create(workspace, "첫 자료", "URL", "https://example.com/one", "");
        sources.create(workspace, "둘째 자료", "URL", "https://example.com/two", "");
        sources.configurePolling(workspace, first.id(), true, 7200, 0);
        assertThat(sources.workspacePolling(workspace).mixed()).isTrue();
        assertThat(sources.list(workspace).stream().filter(source -> source.pollingEnabled()).count()).isEqualTo(1);
        sources.configureWorkspacePolling(workspace, false, 0);
        assertThat(sources.workspacePolling(workspace).mixed()).isFalse();
        assertThat(sources.list(workspace)).allMatch(source -> !source.pollingEnabled());
        assertThat(sourceStore.find(workspace, first.id()).orElseThrow().pollIntervalSeconds()).isEqualTo(7200);
    }

    @Test
    void workspacePollingEndpointValidatesAndConflictsWithoutSendingToAi() throws Exception {
        String workspace = library.createWorkspace("토글 API").id();
        String path = "/api/workspaces/" + workspace + "/source-polling";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(patch(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"enabled\":true,\"expectedRevision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(patch(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"enabled\":false,\"expectedRevision\":0}")).andExpect(status().isConflict());
        mvc.perform(patch(path).header("X-Forge-Client", "1").contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        assertThat(sources.workspacePolling(workspace).enabled()).isTrue();
    }

    @Test
    void workspaceIntervalPersistsWhileOffAndIsInheritedByNewSources() {
        String workspace = library.createWorkspace("Timer fixture").id();
        Source first = local(workspace, "First", "");
        assertThat(first.createdAt()).isNotBlank();
        assertThat(sources.workspacePolling(workspace).intervalSeconds()).isEqualTo(3600);
        sources.configureWorkspacePolling(workspace, false, 900, 0);
        assertThat(sources.workspacePolling(workspace).intervalSeconds()).isEqualTo(900);
        Source second = local(workspace, "Second", "");
        assertThat(second.pollIntervalSeconds()).isEqualTo(900);
        assertThat(second.nextPollAt()).isNull();
        sources.configureWorkspacePolling(workspace, true, 1);
        assertThat(sources.list(workspace)).allSatisfy(source -> {
            assertThat(source.pollingEnabled()).isTrue();
            assertThat(source.pollIntervalSeconds()).isEqualTo(900);
            assertThat(source.nextPollAt()).isNotNull();
        });
        assertThatThrownBy(() -> sources.configureWorkspacePolling(workspace, true, 300, 1))
                .isInstanceOf(LibraryException.class);
        assertThat(sources.workspacePolling(workspace).intervalSeconds()).isEqualTo(900);
        sources.configureWorkspacePolling(workspace, true, 300, 2);
        assertThat(sources.list(workspace)).allMatch(source -> source.pollIntervalSeconds() == 300);
        sources.configureWorkspacePolling(workspace, false, 3);
        assertThat(sources.list(workspace)).allMatch(source -> source.nextPollAt() == null);
        assertThat(sources.workspacePolling(workspace).intervalSeconds()).isEqualTo(300);
        assertThat(sourceStore.find(workspace, first.id()).orElseThrow().createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void workspaceIntervalRejectsInvalidValuesWithoutChangingSettings() throws Exception {
        String workspace = library.createWorkspace("Timer bounds").id();
        for (int interval : new int[] { 0, 59, 86401 }) {
            mvc.perform(patch("/api/workspaces/" + workspace + "/source-polling")
                    .header("X-Forge-Client", "1").contentType("application/json")
                    .content("{\"enabled\":true,\"intervalSeconds\":" + interval + ",\"expectedRevision\":0}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(sources.workspacePolling(workspace).revision()).isZero();
        assertThat(sources.workspacePolling(workspace).enabled()).isFalse();
    }

    private Source local(String workspaceId, String name, String domain) {
        return sources.create(workspaceId, name, "LOCAL_FOLDER", fixture.toString(), domain);
    }

    @Test
    void metadataEditPreservesSourceFilesAndPublicationWhileReindexingDomains() throws Exception {
        Files.writeString(fixture.resolve("notes.txt"), "telescope synthetic evidence");
        String workspace = library.createWorkspace("Metadata fixture").id();
        Source original = sources.sync(workspace, local(workspace, "Before", "old-domain").id());
        String file = entryId(original.id(), "notes.txt");
        var document = knowledge.create(workspace, "telescope", "telescope [E1]", List.of(
                new dev.dougle.knowledge.domain.Knowledge.Citation(file, 1, 1)));
        publications.review(workspace, document.card().id(), 1, "Fixture", "synthetic", true, true);
        var publication = publications.publish(workspace, document.card().id(), 1, 0);
        search.refresh();
        var bundle = bundles.create(workspace, "telescope", List.of("old-domain"), false);
        assertThat(bundleValidator.check(workspace, bundle.id()).eligible()).isTrue();
        clearInvocations(analyzer);
        mvc.perform(patch("/api/workspaces/{workspace}/sources/{id}", workspace, original.id())
                        .header("X-Forge-Client", "1").contentType("application/json")
                        .content("{\"name\":\" 새 이름 \",\"domain\":\"new-domain\",\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.pollingRevision").value(1));
        Source edited = sources.list(workspace).getFirst();
        assertThat(edited.revision()).isEqualTo(original.revision());
        assertThat(edited.syncedAt()).isEqualTo(original.syncedAt());
        assertThat(edited.location()).isEqualTo(original.location());
        assertThat(edited.pollingEnabled()).isFalse();
        assertThat(entryId(original.id(), "notes.txt")).isEqualTo(file);
        assertThat(jdbc.queryForObject("SELECT name FROM entry WHERE id=?", String.class, original.rootEntryId())).isEqualTo("새 이름");
        assertThat(knowledge.get(workspace, document.card().id(), null).version()).isEqualTo(document.version());
        assertThat(publications.state(workspace, document.card().id()).publication()).isEqualTo(publication.publication());
        assertThat(search.search(workspace, "telescope", List.of("old-domain"), true)).isEmpty();
        assertThat(search.search(workspace, "telescope", List.of("new-domain"), true)).hasSize(2);
        assertThat(bundles.get(workspace, bundle.id())).isEqualTo(bundle);
        assertThat(bundleValidator.check(workspace, bundle.id()).eligible()).isFalse();
        verify(analyzer, never()).analyze(anyString(), any());
        assertThat(sources.sync(workspace, original.id()).revision()).isEqualTo(original.revision());
        assertThat(entryId(original.id(), "notes.txt")).isEqualTo(file);
    }

    @Test
    void metadataEditRejectsStaleSettingsSyncingAndCrossWorkspace() throws Exception {
        String workspace = library.createWorkspace("Metadata conflicts").id();
        Source original = local(workspace, "Before", "");
        sources.configurePolling(workspace, original.id(), true, 120, 0);
        assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), "After", "", 0)).isInstanceOf(LibraryException.class);
        Source edited = sources.editMetadata(workspace, original.id(), "After", "domain", 1);
        assertThat(edited.pollingRevision()).isEqualTo(2);
        assertThat(edited.pollingEnabled()).isTrue();
        assertThat(edited.pollIntervalSeconds()).isEqualTo(120);
        assertThatThrownBy(() -> sources.configurePolling(workspace, original.id(), false, 120, 1)).isInstanceOf(LibraryException.class);
        assertThat(sourceStore.begin(workspace, original.id())).isTrue();
        mvc.perform(patch("/api/workspaces/{workspace}/sources/{id}", workspace, original.id())
                        .header("X-Forge-Client", "1").contentType("application/json")
                        .content("{\"name\":\"Blocked\",\"domain\":\"\",\"expectedRevision\":2}"))
                .andExpect(status().isConflict());
        String other = library.createWorkspace("Other metadata").id();
        mvc.perform(patch("/api/workspaces/{workspace}/sources/{id}", other, original.id())
                        .header("X-Forge-Client", "1").contentType("application/json")
                        .content("{\"name\":\"Blocked\",\"domain\":\"\",\"expectedRevision\":2}"))
                .andExpect(status().isNotFound());
        assertThat(sources.list(workspace).getFirst().name()).isEqualTo("After");
    }

    @Test
    void metadataEditValidatesInputAndRequiresMutationGuard() throws Exception {
        String workspace = library.createWorkspace("Metadata validation").id();
        Source original = local(workspace, "Before", "");
        for (String name : List.of(" ", "../escape", "bad\nname", "x".repeat(121))) {
            assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), name, "", 0)).isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), "Valid", null, 0)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), "Valid", "x".repeat(81), 0)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), "Valid", "", null)).isInstanceOf(LibraryException.class);
        mvc.perform(patch("/api/workspaces/{workspace}/sources/{id}", workspace, original.id())
                        .contentType("application/json").content("{\"name\":\"Blocked\",\"domain\":\"\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
        assertThat(sources.list(workspace).getFirst()).isEqualTo(original);
    }

    @Test
    void metadataAndIndexQueueRollBackWhenFolderRenameFails() throws Exception {
        Files.writeString(fixture.resolve("notes.txt"), "telescope rollback fixture");
        String workspace = library.createWorkspace("Metadata rollback").id();
        Source original = sources.sync(workspace, local(workspace, "Before", "old-domain").id());
        search.refresh();
        jdbc.execute("CREATE TRIGGER fixture_block_rename BEFORE UPDATE OF name ON entry BEGIN SELECT RAISE(ABORT, 'fixture rename failure'); END");
        try {
            assertThatThrownBy(() -> sources.editMetadata(workspace, original.id(), "After", "new-domain", 0)).isInstanceOf(RuntimeException.class);
            assertThat(sources.list(workspace).getFirst()).isEqualTo(original);
            assertThat(jdbc.queryForObject("SELECT name FROM entry WHERE id=?", String.class, original.rootEntryId())).isEqualTo("Before");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fixture_block_rename");
        }
    }

    private String entryId(String sourceId, String path) {
        return jdbc.queryForObject("SELECT id FROM entry WHERE source_id=? AND relative_path=?", String.class, sourceId, path);
    }

    @Test
    void url304ReusesPersistedBytesAndFailedCollectionPreservesValidators() throws Exception {
        String workspace = library.createWorkspace("Conditional URL").id();
        URI uri = URI.create("https://example.org/synthetic.txt");
        var validators = new dev.dougle.sources.domain.HttpValidators(uri.toString(), "\"synthetic\"", null);
        byte[] content = "synthetic conditional document".getBytes(StandardCharsets.UTF_8);
        when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, "text/plain", content, false, validators));
        Source source = sources.create(workspace, "Conditional", "URL", uri.toString(), "");
        Source first = sources.sync(workspace, source.id());
        assertThat(first.status()).isEqualTo("READY");
        String original = entryId(source.id(), "synthetic.txt");
        clearInvocations(analyzer);
        when(remote.read(uri, validators)).thenReturn(new RemoteReader.Response(uri, null, new byte[0], true, validators));
        Source unchanged = sources.sync(workspace, source.id());
        assertThat(unchanged.status()).isEqualTo("READY");
        assertThat(unchanged.revision()).isEqualTo(first.revision());
        assertThat(entryId(source.id(), "synthetic.txt")).isEqualTo(original);
        verify(analyzer, never()).analyze(anyString(), any());
        when(remote.read(uri, validators)).thenThrow(new java.io.IOException("synthetic network failure"));
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("ERROR");
        assertThat(sourceStore.snapshot(workspace, source.id()).orElseThrow().validators()).isEqualTo(validators);
        doReturn(new RemoteReader.Response(uri, "text/plain", "new synthetic".getBytes(StandardCharsets.UTF_8))).when(remote).read(uri, validators);
        assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("READY");
        assertThat(sourceStore.snapshot(workspace, source.id()).orElseThrow().validators()).isNull();
        assertThat(entryId(source.id(), "synthetic.txt")).isEqualTo(original);
        sources.delete(workspace, source.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_http_cache", Integer.class)).isZero();
    }

    @Test
    void incrementalCollectorReceivesOnlyItsCommittedSnapshotAfterClaim() {
        String workspace = library.createWorkspace("Synthetic collector").id();
        var seen = new java.util.concurrent.atomic.AtomicReference<dev.dougle.sources.domain.CollectedSnapshot>();
        Collector collector = new Collector() {
            public String kind() { return "GITHUB"; }
            public void validate(String location) {}
            public boolean usesPreviousSnapshot() { return true; }
            public dev.dougle.sources.domain.CollectedSnapshot collect(String location) { throw new AssertionError("Baseline required"); }
            public dev.dougle.sources.domain.CollectedSnapshot collect(String location, dev.dougle.sources.domain.CollectedSnapshot previous) {
                if (previous != null) seen.set(new dev.dougle.sources.domain.CollectedSnapshot(previous.revision(),
                        previous.files().stream().map(file -> new dev.dougle.sources.domain.CollectedSnapshot.File(file.path(), file.content())).toList()));
                return new dev.dougle.sources.domain.CollectedSnapshot("a".repeat(40), List.of(
                        new dev.dougle.sources.domain.CollectedSnapshot.File("synthetic.txt", "synthetic source".getBytes(StandardCharsets.UTF_8))));
            }
        };
        SourceService service = new SourceService(sourceStore, libraryStore, List.of(collector), analyzer);
        Source source = service.create(workspace, "Incremental", "GITHUB", "https://github.com/example/synthetic", "");
        assertThat(service.sync(workspace, source.id()).status()).isEqualTo("READY");
        assertThat(seen.get()).isNull();
        String original = entryId(source.id(), "synthetic.txt");
        assertThat(service.sync(workspace, source.id()).status()).isEqualTo("READY");
        assertThat(seen.get().revision()).isEqualTo("a".repeat(40));
        assertThat(seen.get().files()).hasSize(1);
        assertThat(seen.get().files().get(0).content()).isEqualTo("synthetic source".getBytes(StandardCharsets.UTF_8));
        assertThat(entryId(source.id(), "synthetic.txt")).isEqualTo(original);
    }

    @Test
    void changedRevisionPreservesUnchangedRowsAndAnalyzesOnlyChangedFiles() throws Exception {
        String workspace = library.createWorkspace("Incremental fixture").id();
        Files.createDirectories(fixture.resolve("keep"));
        Files.writeString(fixture.resolve("keep/Stable.java"), "class Stable { void unchangedSymbol() {} }");
        Files.writeString(fixture.resolve("edit.txt"), "obsoleteToken");
        Files.writeString(fixture.resolve("delete.txt"), "deletedToken");
        Source first = sources.sync(workspace, local(workspace, "Incremental", "").id());
        String stable = entryId(first.id(), "keep/Stable.java");
        String folder = entryId(first.id(), "keep");
        String edited = entryId(first.id(), "edit.txt");
        var analysis = library.analysis(workspace, stable);
        var symbols = jdbc.queryForList("SELECT rowid,* FROM symbol WHERE entry_id=?", stable);
        var document = jdbc.queryForMap("SELECT rowid,* FROM extracted_document WHERE entry_id=?", stable);
        byte[] bytes = library.content(workspace, stable);
        clearInvocations(analyzer);
        Files.writeString(fixture.resolve("edit.txt"), "replacementToken");
        Files.delete(fixture.resolve("delete.txt"));
        Files.writeString(fixture.resolve("add.txt"), "addedToken");
        Source changed = sources.sync(workspace, first.id());
        assertThat(changed.status()).isEqualTo("READY");
        assertThat(changed.revision()).isNotEqualTo(first.revision());
        assertThat(entryId(first.id(), "keep/Stable.java")).isEqualTo(stable);
        assertThat(entryId(first.id(), "keep")).isEqualTo(folder);
        assertThat(entryId(first.id(), "edit.txt")).isEqualTo(edited);
        assertThat(library.content(workspace, stable)).isEqualTo(bytes);
        assertThat(library.analysis(workspace, stable)).isEqualTo(analysis);
        assertThat(jdbc.queryForList("SELECT rowid,* FROM symbol WHERE entry_id=?", stable)).isEqualTo(symbols);
        assertThat(jdbc.queryForMap("SELECT rowid,* FROM extracted_document WHERE entry_id=?", stable)).isEqualTo(document);
        verify(analyzer).analyzeStream(eq("edit.txt"), any(java.io.InputStream.class));
        verify(analyzer).analyzeStream(eq("add.txt"), any(java.io.InputStream.class));
        verifyNoMoreInteractions(analyzer);
        search.refresh();
        assertThat(search.search(workspace, "obsoleteToken", List.of())).isEmpty();
        assertThat(search.search(workspace, "deletedToken", List.of())).isEmpty();
        assertThat(search.search(workspace, "replacementToken", List.of())).hasSize(1);
        assertThat(search.search(workspace, "unchangedSymbol", List.of())).hasSize(1);
        clearInvocations(analyzer);
        assertThat(sources.sync(workspace, first.id()).revision()).isEqualTo(changed.revision());
        verifyNoInteractions(analyzer);
    }

    @Test
    void reconcilesFileFolderTransitionsAndEmptySnapshot() throws Exception {
        String workspace = library.createWorkspace("Transitions").id();
        Files.writeString(fixture.resolve("branch"), "fileToken");
        Files.createDirectories(fixture.resolve("tree/deep"));
        Files.writeString(fixture.resolve("tree/deep/leaf.txt"), "leafToken");
        Source first = sources.sync(workspace, local(workspace, "Transitions", "").id());
        Files.delete(fixture.resolve("branch"));
        Files.createDirectory(fixture.resolve("branch"));
        Files.writeString(fixture.resolve("branch/new.txt"), "branchToken");
        Files.delete(fixture.resolve("tree/deep/leaf.txt"));
        Files.delete(fixture.resolve("tree/deep"));
        Files.delete(fixture.resolve("tree"));
        Files.writeString(fixture.resolve("tree"), "treeToken");
        assertThat(sources.sync(workspace, first.id()).status()).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT relative_path FROM entry WHERE source_id=? ORDER BY relative_path", String.class, first.id()))
                .containsExactly("", "branch", "branch/new.txt", "tree");
        search.refresh();
        assertThat(search.search(workspace, "leafToken", List.of())).isEmpty();
        assertThat(search.search(workspace, "branchToken", List.of())).hasSize(1);
        Files.delete(fixture.resolve("branch/new.txt"));
        Files.delete(fixture.resolve("tree"));
        clearInvocations(analyzer);
        Source empty = sources.sync(workspace, first.id());
        assertThat(empty.status()).isEqualTo("READY");
        assertThat(empty.rootEntryId()).isEqualTo(first.rootEntryId());
        assertThat(library.entries(workspace, "ORIGINAL", empty.rootEntryId())).isEmpty();
        verifyNoInteractions(analyzer);
    }

    @Test
    void analysisAndTransactionalWriteFailuresPreservePriorSnapshot() throws Exception {
        String workspace = library.createWorkspace("Atomic synthetic").id();
        Files.writeString(fixture.resolve("keep.txt"), "originalToken");
        Files.writeString(fixture.resolve("delete.txt"), "deleteToken");
        Source first = sources.sync(workspace, local(workspace, "Atomic", "").id());
        String kept = entryId(first.id(), "keep.txt");
        String deleted = entryId(first.id(), "delete.txt");
        Files.writeString(fixture.resolve("keep.txt"), "updatedToken");
        Files.delete(fixture.resolve("delete.txt"));
        doReturn(new Analysis("", "text/plain", "ERROR", "synthetic failure", List.of()))
                .when(analyzer).analyzeStream(eq("keep.txt"), any(java.io.InputStream.class));
        Source failed = sources.sync(workspace, first.id());
        assertThat(failed.status()).isEqualTo("PARTIAL");
        assertThat(libraryStore.entry(workspace, deleted)).isEmpty();
        assertThat(new String(library.content(workspace, kept), StandardCharsets.UTF_8)).isEqualTo("originalToken");
        Source partial = failed;
        doCallRealMethod().when(analyzer).analyzeStream(eq("keep.txt"), any(java.io.InputStream.class));
        Files.writeString(fixture.resolve("keep.txt"), "updatedToken next revision");
        jdbc.execute("CREATE TRIGGER synthetic_reconcile_failure BEFORE UPDATE ON extracted_document BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            failed = sources.sync(workspace, first.id());
            assertThat(failed.status()).isEqualTo("ERROR");
            assertThat(failed.revision()).isEqualTo(partial.revision());
            assertThat(failed.syncedAt()).isEqualTo(partial.syncedAt());
            assertThat(libraryStore.entry(workspace, deleted)).isEmpty();
            assertThat(new String(library.content(workspace, kept), StandardCharsets.UTF_8)).isEqualTo("originalToken");
            search.refresh();
            assertThat(search.search(workspace, "originalToken", List.of())).hasSize(1);
            assertThat(search.search(workspace, "updatedToken", List.of())).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_reconcile_failure");
        }
        assertThat(sources.sync(workspace, first.id()).status()).isEqualTo("READY");
    }

    @Test
    void readsBaselineAfterClaimEvenWhenInitialSourceReadIsStale() throws Exception {
        String workspace = library.createWorkspace("Claim baseline").id();
        Files.writeString(fixture.resolve("stable.txt"), "stableToken");
        Source stale = local(workspace, "Baseline", "");
        Source first = sources.sync(workspace, stale.id());
        SourceStore delayed = mock(SourceStore.class, org.mockito.AdditionalAnswers.delegatesTo(sourceStore));
        doReturn(java.util.Optional.of(stale)).doAnswer(invocation -> sourceStore.find(workspace, stale.id()))
                .when(delayed).find(workspace, stale.id());
        clearInvocations(analyzer);
        Source result = new SourceService(delayed, libraryStore, collectors, analyzer).sync(workspace, stale.id());
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.revision()).isEqualTo(first.revision());
        verifyNoInteractions(analyzer);
        var order = inOrder(delayed);
        order.verify(delayed).find(workspace, stale.id());
        order.verify(delayed).begin(workspace, stale.id());
        order.verify(delayed).find(workspace, stale.id());
        order.verify(delayed).fileHashes(workspace, stale.id());
    }

    @Test
    void revisionOnlyChangeDoesNotRewriteEntriesOrAnalysis() throws Exception {
        String workspace = library.createWorkspace("Revision only").id();
        Collector collector = mock(Collector.class);
        when(collector.kind()).thenReturn("LOCAL_FOLDER");
        var files = List.of(new dev.dougle.sources.domain.CollectedSnapshot.File(
                "Stable.java", "class Stable {}".getBytes(StandardCharsets.UTF_8)));
        when(collector.collect(fixture.toString())).thenReturn(
                new dev.dougle.sources.domain.CollectedSnapshot("synthetic-revision-1", files),
                new dev.dougle.sources.domain.CollectedSnapshot("synthetic-revision-2", files));
        var service = new SourceService(sourceStore, libraryStore, List.of(collector), analyzer);
        Source first = service.sync(workspace, local(workspace, "Revision", "").id());
        String stable = entryId(first.id(), "Stable.java");
        clearInvocations(analyzer);
        jdbc.execute("CREATE TRIGGER synthetic_no_entry_write BEFORE UPDATE ON entry BEGIN SELECT RAISE(ABORT,'unexpected entry write'); END");
        jdbc.execute("CREATE TRIGGER synthetic_no_analysis_write BEFORE UPDATE ON extracted_document BEGIN SELECT RAISE(ABORT,'unexpected analysis write'); END");
        try {
            Source next = service.sync(workspace, first.id());
            assertThat(next.status()).isEqualTo("READY");
            assertThat(next.revision()).isEqualTo("synthetic-revision-2");
            assertThat(next.rootEntryId()).isEqualTo(first.rootEntryId());
            assertThat(entryId(first.id(), "Stable.java")).isEqualTo(stable);
            verifyNoInteractions(analyzer);
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_no_entry_write");
            jdbc.execute("DROP TRIGGER synthetic_no_analysis_write");
        }
    }

    @Test
    void folderCollectionIndexesKoreanTextAndJavaSymbolsWithoutChangingOriginals() throws Exception {
        String workspaceId = library.createWorkspace("실험").id();
        Files.createDirectories(fixture.resolve("notes"));
        Files.writeString(fixture.resolve("notes/관측.md"), "온실의 온도 관측 기록입니다.");
        String java = "package synthetic; public class Greenhouse { public int measureTemperature() { return 21; } }";
        Files.writeString(fixture.resolve("Greenhouse.java"), java);
        Files.writeString(fixture.resolve(".env"), "SYNTHETIC_SECRET=excluded");
        Files.createDirectories(fixture.resolve(".git"));
        Files.writeString(fixture.resolve(".git/config"), "ignored");
        Source registered = local(workspaceId, "연구 자료", "실험");
        assertThat(registered.pollingEnabled()).isFalse();
        assertThat(registered.status()).isEqualTo("NEW");
        Source result = sources.sync(workspaceId, registered.id());
        search.refresh();
        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.revision()).hasSize(64);
        assertThat(Files.readString(fixture.resolve("Greenhouse.java"))).isEqualTo(java);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM entry WHERE kind='FILE'", Integer.class)).isEqualTo(2);
        var hits = search.search(workspaceId, "measureTemperature", List.of());
        assertThat(hits).hasSize(1);
        assertThat(library.analysis(workspaceId, hits.getFirst().entryId()).symbols())
                .extracting(symbol -> symbol.name()).contains("Greenhouse", "measureTemperature");
        assertThat(search.search(workspaceId, "관측", List.of("실험"))).hasSize(1);
        assertThat(search.search(workspaceId, "관측", List.of("다른 분류"))).isEmpty();
        assertThat(search.search(workspaceId, "SYNTHETIC_SECRET", List.of())).isEmpty();
        assertThat(library.entries(workspaceId, "KNOWLEDGE", null)).isEmpty();
    }

    @Test
    void partialResyncPreservesHealthyContentsAndSearchResults() throws Exception {
        String workspaceId = library.createWorkspace("오류 복구").id();
        Files.writeString(fixture.resolve("notes.md"), "별자리 관측 기록");
        Source first = sources.sync(workspaceId, local(workspaceId, "원본", "").id());
        search.refresh();
        Files.createSymbolicLink(fixture.resolve("link"), fixture.resolve("notes.md"));
        Source failed = sources.sync(workspaceId, first.id());
        search.refresh();
        assertThat(failed.status()).isEqualTo("PARTIAL");
        assertThat(failed.revision()).isEqualTo(first.revision());
        assertThat(failed.syncedAt()).isNotNull();
        assertThat(failed.error()).contains("심볼릭");
        assertThat(search.search(workspaceId, "별자리", List.of())).hasSize(1);
    }

    @Test
    void unchangedSyncIsIdempotentAndChangedSnapshotRemovesStaleSearchHits() throws Exception {
        String workspaceId = library.createWorkspace("갱신").id();
        Files.writeString(fixture.resolve("old.md"), "천문 관측");
        Source first = sources.sync(workspaceId, local(workspaceId, "노트", "").id());
        search.refresh();
        String originalId = search.search(workspaceId, "천문", List.of()).getFirst().entryId();
        Source same = sources.sync(workspaceId, first.id());
        search.refresh();
        assertThat(same.revision()).isEqualTo(first.revision());
        assertThat(search.search(workspaceId, "천문", List.of()).getFirst().entryId()).isEqualTo(originalId);
        Files.delete(fixture.resolve("old.md"));
        Files.writeString(fixture.resolve("new.md"), "식물 성장 실험");
        Source changed = sources.sync(workspaceId, first.id());
        search.refresh();
        assertThat(changed.revision()).isNotEqualTo(first.revision());
        assertThat(search.search(workspaceId, "천문", List.of())).isEmpty();
        assertThat(search.search(workspaceId, "식물", List.of())).hasSize(1);
    }

    @Test
    void multipleDomainsAreUnionedAndWorkspacesAreIsolated() throws Exception {
        String workspaceId = library.createWorkspace("도메인").id();
        Files.writeString(fixture.resolve("notes.txt"), "관측 기록");
        sources.sync(workspaceId, local(workspaceId, "천문 자료", "천문").id());
        sources.sync(workspaceId, local(workspaceId, "식물 자료", "식물").id());
        String other = library.createWorkspace("독립 서재").id();
        search.refresh();
        assertThat(search.search(workspaceId, "관측", List.of("천문", "식물"))).hasSize(2);
        assertThat(search.search(other, "관측", List.of())).isEmpty();
        assertThatThrownBy(() -> search.search("missing", "관측", List.of())).isInstanceOf(LibraryException.class);
    }

    @Test
    void sourceOwnedEntriesAreProtectedAndDeletingSourceKeepsManualUploads() throws Exception {
        String workspaceId = library.createWorkspace("관리").id();
        Files.writeString(fixture.resolve("notes.md"), "수집 원문");
        Source source = sources.sync(workspaceId, local(workspaceId, "수집 폴더", "").id());
        assertThatThrownBy(() -> library.deleteEntry(workspaceId, source.rootEntryId()))
                .isInstanceOf(LibraryException.class).hasMessageContaining("Source");
        assertThatThrownBy(() -> library.upload(workspaceId, source.rootEntryId(), "manual.txt", new byte[0]))
                .isInstanceOf(LibraryException.class);
        library.upload(workspaceId, null, "manual.md", "개인 메모".getBytes(StandardCharsets.UTF_8));
        sources.delete(workspaceId, source.id());
        search.refresh();
        assertThat(library.entries(workspaceId, "ORIGINAL", null)).extracting(entry -> entry.name()).containsExactly("manual.md");
        assertThat(Files.exists(fixture.resolve("notes.md"))).isTrue();
        assertThat(search.search(workspaceId, "수집", List.of())).isEmpty();
    }

    @Test
    void boundedUrlCollectionUsesPersistedTextAndDoesNotRefetchDuringSearch() throws Exception {
        String workspaceId = library.createWorkspace("웹 자료").id();
        URI uri = URI.create("https://example.org/notes");
        when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, "text/html",
                "<html><body><h1>가상 온실</h1><p>식물 성장 관찰</p></body></html>".getBytes(StandardCharsets.UTF_8)));
        Source source = sources.create(workspaceId, "웹 문서", "URL", uri.toString(), "실험");
        assertThat(sources.sync(workspaceId, source.id()).status()).isEqualTo("READY");
        search.refresh();
        for (int count = 0; count < 3; count++) assertThat(search.search(workspaceId, "식물", List.of())).hasSize(1);
        verify(remote, times(1)).read(uri);
    }

    @Test
    void remoteFailureDoesNotReplacePreviouslyPublishedBytes() throws Exception {
        String workspaceId = library.createWorkspace("웹 오류").id();
        URI uri = URI.create("https://example.org/notes");
        when(remote.read(uri)).thenReturn(new RemoteReader.Response(uri, "text/plain", "유효 원문".getBytes(StandardCharsets.UTF_8)));
        Source first = sources.sync(workspaceId, sources.create(workspaceId, "웹", "URL", uri.toString(), "").id());
        when(remote.read(uri)).thenThrow(new java.io.IOException("simulated network error"));
        Source failed = sources.sync(workspaceId, first.id());
        assertThat(failed.status()).isEqualTo("ERROR");
        assertThat(failed.revision()).isEqualTo(first.revision());
        assertThat(library.entries(workspaceId, "ORIGINAL", first.rootEntryId())).hasSize(1);
    }

    @Test
    void duplicateRootPublicationRollsBackAndPreservesManualFolder() throws Exception {
        String workspaceId = library.createWorkspace("충돌").id();
        var manual = library.createFolder(workspaceId, null, "충돌 이름");
        Files.writeString(fixture.resolve("notes.md"), "가상 데이터");
        Source source = sources.sync(workspaceId, local(workspaceId, "충돌 이름", "").id());
        assertThat(source.status()).isEqualTo("ERROR");
        assertThat(source.rootEntryId()).isNull();
        assertThat(library.entries(workspaceId, "ORIGINAL", null)).containsExactly(manual);
    }

    @Test
    void rejectsConcurrentSyncAndCrossWorkspaceRequests() throws Exception {
        String first = library.createWorkspace("소유").id();
        String second = library.createWorkspace("다른").id();
        Source source = local(first, "자료", "");
        jdbc.update("UPDATE source SET status='SYNCING' WHERE id=?", source.id());
        mvc.perform(post("/api/workspaces/" + first + "/sources/" + source.id() + "/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/workspaces/" + second + "/sources/" + source.id() + "/sync").header("X-Forge-Client", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/workspaces/" + second + "/sources/" + source.id()).header("X-Forge-Client", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void analysisFailureIsVisibleAndRawBytesRemainDownloadable() {
        String workspaceId = library.createWorkspace("원본 보존").id();
        byte[] invalid = new byte[] {0, 1, 2, 3, (byte) 255};
        var file = library.upload(workspaceId, null, "unknown.bin", invalid);
        assertThat(library.content(workspaceId, file.id())).isEqualTo(invalid);
        assertThat(library.analysis(workspaceId, file.id()).status()).isIn("UNSUPPORTED", "ERROR");
    }

    @Test
    void deletingDuringCollectionCannotResurrectTheSource() throws Exception {
        String workspaceId = library.createWorkspace("동시 삭제").id();
        URI uri = URI.create("https://example.org/pending");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(remote.read(uri)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new RemoteReader.Response(uri, "text/plain", "가상 자료".getBytes(StandardCharsets.UTF_8));
        });
        Source source = sources.create(workspaceId, "동기화 자료", "URL", uri.toString(), "");
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> sources.sync(workspaceId, source.id()));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            sources.delete(workspaceId, source.id());
            release.countDown();
            assertThatThrownBy(() -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LibraryException.class);
        }
        assertThat(sources.list(workspaceId)).isEmpty();
        assertThat(library.entries(workspaceId, "ORIGINAL", null)).isEmpty();
    }
}
