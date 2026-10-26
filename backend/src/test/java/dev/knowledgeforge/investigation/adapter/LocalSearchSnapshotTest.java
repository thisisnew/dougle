package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.search.domain.ContentDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class LocalSearchSnapshotTest {
    final SearchService search = mock(SearchService.class);
    final LibraryStore library = mock(LibraryStore.class);
    final KnowledgeStore knowledge = mock(KnowledgeStore.class);
    final Clock clock = mock(Clock.class);
    final LocalToolExecutorFactory factory = new LocalToolExecutorFactory(search, library, knowledge, clock);
    final String job = "snapshot_job_0001";
    final ToolExecution.Budget budget = new ToolExecution.Budget(1, Duration.ofSeconds(5), Duration.ofSeconds(2), 6000, 6000);
    final Instant now = Instant.parse("2026-10-26T00:00:00Z");

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(now);
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
    }

    SearchIndex.Hit hit(String text, boolean bodyMatch) {
        String id = UUID.randomUUID().toString();
        when(knowledge.material("workspace", id)).thenReturn(Optional.of(new KnowledgeStore.Material(id, null, null,
                null, null, null, null, "fixture", text.getBytes(java.nio.charset.StandardCharsets.UTF_8), text, "READY")));
        return new SearchIndex.Hit(id, "fixture", null, "", null, text, 1, 1, 1, bodyMatch,
                "ORIGINAL", null, null, List.of(), "CURRENT", ContentDigest.of(text));
    }

    @Test
    void fiveNormalHitsFitBudgetAndSnapshotIsExactTypedEmittedEvidence() {
        var hits = java.util.stream.IntStream.range(0, 5).mapToObj(index -> hit("x".repeat(900), true)).toList();
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(hits);
        var result = factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation());
        assertThat(result.status()).isEqualTo(ToolExecution.Status.OK);
        assertThat(result.truncated()).isFalse();
        var snapshot = factory.snapshot("workspace", job, "question");
        assertThat(snapshot.entries()).hasSize(5);
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var emitted = result.output().lines().skip(1).map(mapper::readTree).toList();
        for (int index = 0; index < 5; index++) {
            assertThat(snapshot.entries().get(index).hit().snippet()).hasSize(768).isEqualTo(emitted.get(index).get("content").asString());
            assertThat(snapshot.entries().get(index).hit().entryId()).isEqualTo(emitted.get(index).get("entryId").asString());
        }
        assertThatThrownBy(() -> factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation())).isInstanceOf(LibraryException.class);
        verify(search, times(1)).searchQuestion("workspace", "question", List.of(), false);
    }

    @Test
    void nonBodyCandidatesAreNotEvidenceAndDoNotFailOtherHits() {
        var selected = hit("original excerpt", true);
        var title = hit("title only", false);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(title, selected));
        assertThat(factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation()).status()).isEqualTo(ToolExecution.Status.OK);
        assertThat(factory.snapshot("workspace", job, "question").entries()).extracting(value -> value.hit().entryId()).containsExactly(selected.entryId());
    }

    @Test
    void truncatedOutputCannotPublishEvenIfToolFinished() {
        var selected = hit("x".repeat(900), true);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(selected));
        var tiny = new ToolExecution.Budget(1, Duration.ofSeconds(5), Duration.ofSeconds(2), 100, 100);
        assertThat(factory.search("workspace", job, "question", tiny, new ToolExecution.Cancellation()).truncated()).isTrue();
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
    }

    @Test
    void staleSourceAndForgedExcerptNeverPublish() {
        var selected = hit("actual content", true);
        when(knowledge.material("workspace", selected.entryId())).thenReturn(Optional.empty());
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(selected));
        assertThat(factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation()).status()).isEqualTo(ToolExecution.Status.FAILED);
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
        var forged = new SearchIndex.Hit(selected.entryId(), "fixture", null, "", null, "forged", 1, 1, 1, true,
                "ORIGINAL", null, null, List.of(), "CURRENT", ContentDigest.of("actual content"));
        when(knowledge.material("workspace", selected.entryId())).thenReturn(Optional.of(new KnowledgeStore.Material(selected.entryId(), null, null,
                null, null, null, null, "fixture", new byte[0], "actual content", "READY")));
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(forged));
        assertThat(factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation()).status()).isEqualTo(ToolExecution.Status.FAILED);
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
    }

    @Test
    void scopeQueryJobExpiryAndRestartCannotRecoverSnapshotFromRenderedText() {
        var selected = hit("original", true);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(selected));
        factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation());
        assertThatThrownBy(() -> factory.snapshot("other", job, "question")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> factory.snapshot("workspace", "other", "question")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "changed")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> new LocalToolExecutorFactory(search, library, knowledge, clock).snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
    }

    @Test
    void cancellationFailureAndEmptyResultsCannotPublish() {
        var cancellation = new ToolExecution.Cancellation();
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenAnswer(invocation -> {
            cancellation.cancel(); return List.of(hit("original", true));
        });
        assertThat(factory.search("workspace", job, "question", budget, cancellation).status()).isEqualTo(ToolExecution.Status.CANCELLED);
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of());
        assertThat(factory.search("workspace", job, "question", budget, new ToolExecution.Cancellation()).status()).isEqualTo(ToolExecution.Status.EMPTY);
        assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
    }

    @Test
    void capacityIsBoundedAndDatabaseLookupDoesNotHoldFactoryMonitor() {
        var selected = hit("original", true);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(selected));
        for (int index = 0; index < 32; index++) factory.search("workspace", "snapshot_job_" + String.format("%04d", index), "question", budget, new ToolExecution.Cancellation());
        assertThatThrownBy(() -> factory.search("workspace", "snapshot_job_overflow", "question", budget, new ToolExecution.Cancellation())).isInstanceOf(LibraryException.class);
        when(library.workspace("workspace")).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(factory)).isFalse(); return Optional.of(new Workspace("workspace", "fixture", "now"));
        });
        assertThat(factory.snapshot("workspace", "snapshot_job_0000", "question")).isNotNull();
    }

    @Test
    void lateUncooperativeCompletionAfterCancellationCannotPublish() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var selected = hit("original", true);
        var cancellation = new ToolExecution.Cancellation();
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenAnswer(invocation -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try { release.await(); released = true; }
                catch (InterruptedException ignored) { }
            }
            finished.countDown();
            return List.of(selected);
        });
        try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> factory.search("workspace", job, "question", budget, cancellation));
            try {
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                cancellation.cancel();
                assertThat(result.get(2, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(ToolExecution.Status.CANCELLED);
                assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
            } finally { release.countDown(); }
            assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> factory.snapshot("workspace", job, "question")).isInstanceOf(LibraryException.class);
        }
    }
}
