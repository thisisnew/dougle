package dev.dougle.sources.application;

import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.domain.Source;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;

@Service
public class SourceSyncJobs {
    public record Progress(String id, String state, Source source, String error) {}
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String workspace;
        final String source;
        volatile Progress progress = new Progress(id, "RUNNING", null, null);
        Job(String workspace, String source) { this.workspace = workspace; this.source = source; }
    }
    private final SourceService sources;
    private final SearchService search;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public SourceSyncJobs(SourceService sources, SearchService search) {
        this.sources = sources;
        this.search = search;
    }

    public synchronized Progress start(String workspace, String source) {
        var existing = jobs.values().stream().filter(job -> job.workspace.equals(workspace)
                && job.source.equals(source) && job.progress.state().equals("RUNNING")).findFirst();
        if (existing.isPresent()) return existing.get().progress;
        if (jobs.values().stream().filter(job -> job.progress.state().equals("RUNNING")).count() >= 2)
            throw new LibraryException(429, "다른 자료를 동기화하고 있습니다. 잠시 후 다시 시도해 주세요.");
        while (jobs.size() >= 128) {
            String finished = jobs.values().stream().filter(job -> !job.progress.state().equals("RUNNING"))
                    .findFirst().orElseThrow().id;
            jobs.remove(finished);
        }
        var job = new Job(workspace, source);
        jobs.put(job.id, job);
        workers.submit(() -> {
            try {
                Source result = sources.sync(workspace, source, search::refresh);
                job.progress = new Progress(job.id, "COMPLETED", result, null);
            } catch (Exception exception) {
                job.progress = new Progress(job.id, "FAILED", null, exception instanceof LibraryException
                        ? exception.getMessage() : "자료를 동기화하지 못했습니다. 다시 시도해 주세요.");
            }
        });
        return job.progress;
    }

    public synchronized Progress get(String workspace, String source, String id) {
        Job job = jobs.get(id);
        if (job == null || !job.workspace.equals(workspace) || !job.source.equals(source))
            throw new LibraryException(404, "동기화 작업을 찾을 수 없습니다. 자료 상태를 확인한 뒤 다시 동기화해 주세요.");
        return job.progress;
    }

    @PreDestroy public void close() { workers.shutdownNow(); }
}
