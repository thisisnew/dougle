package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.search.application.ConversationRetrieval;
import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.application.SearchService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class BoundedConversationRetrievalTest {
    private final SearchService search = mock(SearchService.class);

    private static SearchIndex.Hit hit(String id) {
        return new SearchIndex.Hit(id, "synthetic", null, null, "fixture", "text", 1, 1, 1,
                true, "ORIGINAL", null, null, List.of(), "CURRENT", "hash");
    }

    private static ConversationRetrieval.Attempt attempt() { return new ConversationRetrieval.Attempt(Duration.ofSeconds(2)); }

    @Test void interleavesCurrentFirstAndDeduplicatesWholeEntriesWithoutParsingToolText() {
        when(search.searchQuestionCandidates("one", "current")).thenReturn(List.of(hit("current"), hit("shared")));
        when(search.searchQuestionCandidates("one", "previous")).thenReturn(List.of(hit("previous"), hit("shared")));
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            var result = retrieval.retrieve("one", List.of("current", "previous"), attempt());
            assertThat(result.status()).isEqualTo(ConversationRetrieval.Status.OK);
            assertThat(result.hits()).extracting(SearchIndex.Hit::entryId).containsExactly("current", "previous", "shared");
            assertThatThrownBy(() -> result.hits().clear()).isInstanceOf(UnsupportedOperationException.class);
            verify(search).searchQuestionCandidates("one", "current");
            verify(search).searchQuestionCandidates("one", "previous");
            verifyNoMoreInteractions(search);
        }
    }

    @Test void identicalQueriesExecuteOnceAndEmptyIsNotFailure() {
        when(search.searchQuestionCandidates("one", "same")).thenReturn(List.of());
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            assertThat(retrieval.retrieve("one", List.of("same", " same "), attempt()).status()).isEqualTo(ConversationRetrieval.Status.EMPTY);
            verify(search, times(1)).searchQuestionCandidates("one", "same");
        }
    }

    @Test void twoNormalThirtyHitBatchesBudgetRawInputSeparatelyFromUniqueOutput() {
        var current = java.util.stream.IntStream.range(0, 30).mapToObj(index -> hit("entry-" + index)).toList();
        var previous = java.util.stream.IntStream.range(30, 60).mapToObj(index -> hit("entry-" + index)).toList();
        when(search.searchQuestionCandidates("one", "current")).thenReturn(current);
        when(search.searchQuestionCandidates("one", "previous")).thenReturn(current, previous);
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            var overlap = retrieval.retrieve("one", List.of("current", "previous"), attempt());
            assertThat(overlap.status()).isEqualTo(ConversationRetrieval.Status.OK);
            assertThat(overlap.hits()).containsExactlyElementsOf(current);
            var disjoint = retrieval.retrieve("one", List.of("current", "previous"), attempt());
            assertThat(disjoint.status()).isEqualTo(ConversationRetrieval.Status.OK);
            assertThat(disjoint.hits()).hasSize(60);
            assertThat(disjoint.hits().subList(0, 4)).containsExactly(current.get(0), previous.get(0), current.get(1), previous.get(1));
        }
    }

    @Test void duplicatedLargeHitUsesUniqueCharacterBudgetButRawInputStillHasIndependentCap() {
        var large = hit("x".repeat(70000));
        when(search.searchQuestionCandidates("one", "current")).thenReturn(List.of(large));
        when(search.searchQuestionCandidates("one", "previous")).thenReturn(List.of(large));
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            var overlap = retrieval.retrieve("one", List.of("current", "previous"), attempt());
            assertThat(overlap.status()).isEqualTo(ConversationRetrieval.Status.OK);
            assertThat(overlap.hits()).containsExactly(large);
            when(search.searchQuestionCandidates("one", "current")).thenReturn(java.util.Collections.nCopies(4, large));
            assertThat(retrieval.retrieve("one", List.of("current"), attempt()).status()).isEqualTo(ConversationRetrieval.Status.DENIED);
        }
    }

    @Test void queryAndWorkspaceBoundsDenyBeforeAnySearch() {
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            for (var queries : List.of(List.<String>of(), List.of("one", "two", "three"), List.of(" "), List.of("x".repeat(257)))) {
                assertThat(retrieval.retrieve("one", queries, attempt()).status()).isEqualTo(ConversationRetrieval.Status.DENIED);
            }
            assertThat(retrieval.retrieve("", List.of("question"), attempt()).status()).isEqualTo(ConversationRetrieval.Status.DENIED);
            verifyNoInteractions(search);
        }
    }

    @Test void failureOfSecondSearchDiscardsFirstResultsInsteadOfReturningEmptyOrPartialEvidence() {
        when(search.searchQuestionCandidates("one", "current")).thenReturn(List.of(hit("first")));
        when(search.searchQuestionCandidates("one", "previous")).thenThrow(new IllegalStateException("private-error"));
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            var result = retrieval.retrieve("one", List.of("current", "previous"), attempt());
            assertThat(result.status()).isEqualTo(ConversationRetrieval.Status.FAILED);
            assertThat(result.hits()).isEmpty();
            assertThat(result.toString()).doesNotContain("private-error");
        }
    }

    @Test void typedPayloadBoundsRejectRatherThanSilentlyTruncatingEvidence() {
        var huge = hit("x".repeat(BoundedConversationRetrieval.MAX_CHARACTERS + 1));
        when(search.searchQuestionCandidates("one", "large")).thenReturn(List.of(huge));
        when(search.searchQuestionCandidates("one", "many")).thenReturn(java.util.Collections.nCopies(49, hit("entry")));
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            for (String query : List.of("large", "many")) {
                var result = retrieval.retrieve("one", List.of(query), attempt());
                assertThat(result.status()).isEqualTo(ConversationRetrieval.Status.DENIED);
                assertThat(result.hits()).isEmpty();
            }
        }
    }

    @Test void twoCallsShareDeadlineAndLateSecondResultIsNeverReturned() throws Exception {
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(search.searchQuestionCandidates("one", "first")).thenAnswer(call -> {
            Thread.sleep(90);
            return List.of(hit("first"));
        });
        when(search.searchQuestionCandidates("one", "second")).thenAnswer(call -> {
            secondEntered.countDown();
            awaitIgnoringInterrupt(release);
            finished.countDown();
            return List.of(hit("late"));
        });
        try (var retrieval = new BoundedConversationRetrieval(search)) {
            try {
                var result = retrieval.retrieve("one", List.of("first", "second"), new ConversationRetrieval.Attempt(Duration.ofMillis(300)));
                assertThat(secondEntered.getCount()).isZero();
                assertThat(result.status()).isEqualTo(ConversationRetrieval.Status.TIMEOUT);
                assertThat(result.hits()).isEmpty();
                release.countDown();
                assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(result.hits()).isEmpty();
            } finally { release.countDown(); }
        }
    }

    @Test void cancellationFlagStopsWaitingAndPreCancelledAttemptMakesNoCall() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(search.searchQuestionCandidates(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return List.of(hit("late"));
        });
        try (var retrieval = new BoundedConversationRetrieval(search); var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var control = attempt();
            try {
                var pending = tasks.submit(() -> retrieval.retrieve("one", List.of("question"), control));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                control.cancel();
                assertThat(pending.get(1, TimeUnit.SECONDS).status()).isEqualTo(ConversationRetrieval.Status.CANCELLED);
                assertThat(retrieval.retrieve("one", List.of("another"), control).status()).isEqualTo(ConversationRetrieval.Status.CANCELLED);
                verify(search, never()).searchQuestionCandidates("one", "another");
            } finally { release.countDown(); }
        }
    }

    @Test void uncooperativeWorkersCannotGrowPoolOrQueueAcrossRepeatedTimeouts() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        when(search.searchQuestionCandidates(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            finished.countDown();
            return List.of();
        });
        try (var retrieval = new BoundedConversationRetrieval(search); var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = tasks.submit(() -> retrieval.retrieve("one", List.of("first"), new ConversationRetrieval.Attempt(Duration.ofMillis(400))));
                var second = tasks.submit(() -> retrieval.retrieve("one", List.of("second"), new ConversationRetrieval.Attempt(Duration.ofMillis(400))));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(first.get(1, TimeUnit.SECONDS).status()).isEqualTo(ConversationRetrieval.Status.TIMEOUT);
                assertThat(second.get(1, TimeUnit.SECONDS).status()).isEqualTo(ConversationRetrieval.Status.TIMEOUT);
                for (int count = 0; count < 20; count++) {
                    assertThat(retrieval.retrieve("one", List.of("blocked"), attempt()).status()).isEqualTo(ConversationRetrieval.Status.DENIED);
                }
                verify(search, never()).searchQuestionCandidates("one", "blocked");
            } finally { release.countDown(); }
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try { release.await(20, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { }
        }
        if (release.getCount() > 0) throw new IllegalStateException("synthetic worker deadline");
    }
}
