package dev.dougle.search.application;

import dev.dougle.library.application.LibraryException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public interface ConversationRetrieval {
    enum Status { OK, EMPTY, DENIED, TIMEOUT, FAILED, CANCELLED }
    record Result(Status status, List<SearchIndex.Hit> hits) {
        public Result {
            hits = List.copyOf(hits);
            if (status != Status.OK && !hits.isEmpty()) throw new IllegalArgumentException("Unsuccessful search cannot publish hits");
        }
    }

    final class Attempt {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long started = System.nanoTime();
        private final long nanoseconds;

        public Attempt(Duration limit) {
            if (limit.isZero() || limit.isNegative() || limit.compareTo(Duration.ofSeconds(10)) > 0) {
                throw new IllegalArgumentException("Local search deadline must be within ten seconds");
            }
            nanoseconds = limit.toNanos();
        }

        public void cancel() { cancelled.set(true); }
        public boolean cancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
        public long remaining() { return nanoseconds - (System.nanoTime() - started); }
        public void checkpoint() {
            if (cancelled()) throw new LibraryException(409, "LOCAL_SEARCH_CANCELLED: 로컬 검색을 취소했습니다.");
            if (remaining() <= 0) throw new LibraryException(504, "LOCAL_SEARCH_TIMEOUT: 로컬 검색 시간 한도를 초과했습니다.");
        }
    }

    Result retrieve(String workspaceId, List<String> queries, Attempt attempt);
}
