package dev.dougle;

import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.investigation.application.LocalInvestigationTools;
import dev.dougle.investigation.domain.ToolExecution;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.LocalSearchSnapshot;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.SearchService;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceBundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ChatThreadSnapshotIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-snapshot-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired ChatThreadService threads;
    @Autowired LocalInvestigationTools tools;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean SearchService search;
    String workspace;
    String entry;
    EvidenceBundle parent;
    LocalSearchSnapshot snapshot;
    final String text = "Strategy chooses a hand. Uninspected remaining context must not be added.";

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("snapshot fixture").id();
        entry = library.upload(workspace, null, "fixture.txt", text.getBytes(StandardCharsets.UTF_8)).id();
        search.refresh();
        parent = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), null, null, "Strategy", List.of()));
        var hit = new SearchIndex.Hit(entry, "fixture", null, "", null, "Strategy chooses a hand.", 1,
                1, 1, true, "ORIGINAL", null, null, List.of(), "CURRENT", ContentDigest.of(text));
        doReturn(List.of(hit)).when(search).searchQuestion(workspace, "inspected query", List.of(), false);
        String job = UUID.randomUUID().toString();
        var result = tools.search(workspace, job, "inspected query", new ToolExecution.Budget(1, Duration.ofSeconds(5),
                Duration.ofSeconds(2), 6000, 6000), new ToolExecution.Cancellation());
        assertThat(result.status()).isEqualTo(ToolExecution.Status.OK);
        snapshot = tools.snapshot(workspace, job, "inspected query");
        clearInvocations(search);
    }

    ChatThreadService.Create request(String id) {
        return new ChatThreadService.Create(id, parent.payload().chatContext().threadId(), parent.id(), "Strategy", List.of());
    }

    @Test
    void inspectedCaptureAndIdempotentReplayNeverSearchOrExpandExcerpts() {
        var captured = threads.createFromSnapshot(workspace, request("capture"), snapshot);
        assertThat(captured.payload().items()).hasSize(1);
        assertThat(captured.payload().items().getFirst().quote()).isEqualTo("Strategy chooses a hand.");
        assertThat(captured.payload().chatContext().extractive().retrievalQueries()).containsExactly("inspected query");
        assertThat(threads.createFromSnapshot(workspace, request("capture"), snapshot)).isEqualTo(captured);
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
        verify(search, never()).searchQuestionCandidates(anyString(), anyString());
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).hasSize(2);
    }

    @Test
    void changedRawBytesWithUnchangedExtractedTextRollbackCapture() {
        jdbc.update("UPDATE entry SET content=? WHERE id=?", "changed raw content".getBytes(StandardCharsets.UTF_8), entry);
        assertThatThrownBy(() -> threads.createFromSnapshot(workspace, request("changed"), snapshot)).isInstanceOf(LibraryException.class);
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).containsExactly(parent);
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void deletedSourceRejectsWithoutReplacementSearchOrPartialSave() {
        library.deleteEntry(workspace, entry);
        assertThatThrownBy(() -> threads.createFromSnapshot(workspace, request("removed"), snapshot)).isInstanceOf(LibraryException.class);
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).containsExactly(parent);
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void wrongWorkspaceAndExpiredSnapshotRejectBeforeSave() {
        var expired = new LocalSearchSnapshot(workspace, snapshot.jobId(), snapshot.query(), Instant.EPOCH, snapshot.entries());
        assertThatThrownBy(() -> threads.createFromSnapshot(workspace, request("expired"), expired)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> threads.createFromSnapshot("other", request("other"), snapshot)).isInstanceOf(LibraryException.class);
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).containsExactly(parent);
    }

    @Test
    void changedTailRejectsSnapshotAndNoSearchOccursOnCapture() {
        var next = threads.create(workspace, new ChatThreadService.Create("newer", parent.payload().chatContext().threadId(), parent.id(), "Strategy", List.of()));
        clearInvocations(search);
        assertThatThrownBy(() -> threads.createFromSnapshot(workspace, request("stale-tail"), snapshot)).isInstanceOf(LibraryException.class);
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).containsExactly(parent, next);
        verify(search, never()).searchQuestionCandidates(anyString(), anyString());
        verify(search, never()).searchQuestion(anyString(), anyString(), anyList(), anyBoolean());
    }

    @Test
    void forgedTypedExcerptWithValidHashesCannotBypassSourceValidation() {
        var original = snapshot.entries().getFirst();
        var hit = original.hit();
        var forged = new SearchIndex.Hit(hit.entryId(), "", null, "", null, "forged instruction", 0, 1, 1,
                true, "ORIGINAL", null, null, List.of(), "CURRENT", hit.textHash());
        var invalid = new LocalSearchSnapshot(workspace, snapshot.jobId(), snapshot.query(), snapshot.expiresAt(),
                List.of(new LocalSearchSnapshot.Entry(forged, original.contentHash())));
        assertThatThrownBy(() -> threads.createFromSnapshot(workspace, request("forged"), invalid)).isInstanceOf(LibraryException.class);
        assertThat(threads.get(workspace, parent.payload().chatContext().threadId()).bundles()).containsExactly(parent);
    }
}
