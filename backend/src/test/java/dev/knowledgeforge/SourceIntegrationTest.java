package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.application.SourceService;
import dev.knowledgeforge.sources.domain.Source;
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
    @Autowired SourceService sources;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @Autowired DocumentAnalyzer analyzer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean RemoteReader remote;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
    }

    private Source local(String workspaceId, String name, String domain) {
        return sources.create(workspaceId, name, "LOCAL_FOLDER", fixture.toString(), domain);
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
    void failedResyncPreservesRevisionContentsAndSearchResults() throws Exception {
        String workspaceId = library.createWorkspace("오류 복구").id();
        Files.writeString(fixture.resolve("notes.md"), "별자리 관측 기록");
        Source first = sources.sync(workspaceId, local(workspaceId, "원본", "").id());
        search.refresh();
        Files.createSymbolicLink(fixture.resolve("link"), fixture.resolve("notes.md"));
        Source failed = sources.sync(workspaceId, first.id());
        search.refresh();
        assertThat(failed.status()).isEqualTo("ERROR");
        assertThat(failed.revision()).isEqualTo(first.revision());
        assertThat(failed.syncedAt()).isEqualTo(first.syncedAt());
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
