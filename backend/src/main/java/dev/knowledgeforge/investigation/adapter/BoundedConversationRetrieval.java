package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.search.application.ConversationRetrieval;
import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.application.SearchService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public final class BoundedConversationRetrieval implements ConversationRetrieval, AutoCloseable {
    static final int MAX_HITS_PER_QUERY = 30;
    static final int MAX_HITS = 60;
    static final int MAX_CHARACTERS = 131072;
    private final SearchService search;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                var thread = new Thread(task, "conversation-local-search");
                thread.setDaemon(true);
                return thread;
            });

    public BoundedConversationRetrieval(SearchService search) { this.search = search; }

    @Override public Result retrieve(String workspaceId, List<String> queries, Attempt attempt) {
        if (workspaceId == null || workspaceId.isBlank() || queries == null || queries.isEmpty() || queries.size() > 2
                || queries.stream().anyMatch(query -> query == null || query.isBlank() || query.length() > 256)) {
            return result(Status.DENIED);
        }
        var distinct = queries.stream().map(String::strip).distinct().toList();
        if (attempt.cancelled()) return result(Status.CANCELLED);
        if (attempt.remaining() <= 0) return result(Status.TIMEOUT);
        java.util.concurrent.Future<Result> future;
        try { future = workers.submit(() -> search(workspaceId, distinct, attempt)); }
        catch (RejectedExecutionException unavailable) { return result(Status.DENIED); }
        try {
            while (true) {
                if (attempt.cancelled()) return result(Status.CANCELLED);
                long remaining = attempt.remaining();
                if (remaining <= 0) return result(Status.TIMEOUT);
                try {
                    var completed = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
                    if (attempt.cancelled()) return result(Status.CANCELLED);
                    return attempt.remaining() <= 0 ? result(Status.TIMEOUT) : completed;
                } catch (TimeoutException waiting) { }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            attempt.cancel();
            return result(Status.CANCELLED);
        } catch (CancellationException cancelled) {
            return result(Status.CANCELLED);
        } catch (ExecutionException failed) {
            return result(attempt.cancelled() ? Status.CANCELLED : attempt.remaining() <= 0 ? Status.TIMEOUT : Status.FAILED);
        } finally { future.cancel(true); }
    }

    private Result search(String workspaceId, List<String> queries, Attempt attempt) {
        var batches = new ArrayList<List<SearchIndex.Hit>>();
        int characters = 0;
        for (String query : queries) {
            attempt.checkpoint();
            var found = search.searchQuestionCandidates(workspaceId, query);
            attempt.checkpoint();
            if (found.size() > MAX_HITS_PER_QUERY) return result(Status.DENIED);
            var snapshot = new ArrayList<SearchIndex.Hit>();
            for (var hit : found) {
                attempt.checkpoint();
                if (!"ORIGINAL".equals(hit.kind()) || hit.entryId() == null || hit.textHash() == null
                        || hit.snippet() == null) return result(Status.DENIED);
                for (String value : new String[] {hit.entryId(), hit.name(), hit.sourceId(), hit.domain(), hit.path(),
                        hit.snippet(), hit.kind(), hit.cardId(), hit.freshness(), hit.textHash()}) {
                    if (value != null) {
                        if (value.length() > MAX_CHARACTERS * 2 - characters) return result(Status.DENIED);
                        characters += value.length();
                    }
                }
                if (hit.domains() == null || hit.domains().size() > 20) return result(Status.DENIED);
                for (String domain : hit.domains()) {
                    if (domain == null || domain.length() > MAX_CHARACTERS * 2 - characters) return result(Status.DENIED);
                    characters += domain.length();
                }
                snapshot.add(new SearchIndex.Hit(hit.entryId(), hit.name(), hit.sourceId(), hit.domain(), hit.path(), hit.snippet(),
                        hit.score(), hit.startLine(), hit.endLine(), hit.bodyMatch(), hit.kind(), hit.cardId(), hit.version(),
                        List.copyOf(hit.domains()), hit.freshness(), hit.textHash()));
            }
            batches.add(List.copyOf(snapshot));
        }
        var merged = new LinkedHashMap<String, SearchIndex.Hit>();
        int ranks = batches.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < ranks; rank++) {
            for (var batch : batches) if (rank < batch.size()) merged.putIfAbsent(batch.get(rank).entryId(), batch.get(rank));
        }
        attempt.checkpoint();
        if (merged.size() > MAX_HITS || merged.values().stream().mapToLong(BoundedConversationRetrieval::characters).sum() > MAX_CHARACTERS) {
            return result(Status.DENIED);
        }
        return merged.isEmpty() ? result(Status.EMPTY) : new Result(Status.OK, List.copyOf(merged.values()));
    }

    private static long characters(SearchIndex.Hit hit) {
        long size = hit.domains().stream().mapToLong(String::length).sum();
        for (String value : new String[] {hit.entryId(), hit.name(), hit.sourceId(), hit.domain(), hit.path(),
                hit.snippet(), hit.kind(), hit.cardId(), hit.freshness(), hit.textHash()}) {
            if (value != null) size += value.length();
        }
        return size;
    }

    private static Result result(Status status) { return new Result(status, List.of()); }

    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
