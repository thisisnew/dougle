package dev.dougle.sources.adapter;

import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.application.SourceService;
import dev.dougle.sources.domain.Source;
import dev.dougle.sources.domain.WorkspacePolling;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class SourceController {
    private final SourceService sources;
    private final SearchService search;
    private final dev.dougle.sources.application.SourceSyncJobs syncJobs;

    public SourceController(SourceService sources, SearchService search, dev.dougle.sources.application.SourceSyncJobs syncJobs) {
        this.sources = sources;
        this.search = search;
        this.syncJobs = syncJobs;
    }

    public record Registration(String name, String kind, String location, String domain, Boolean sync) {}
    public record PollingSettings(Boolean enabled, Integer intervalSeconds, Integer expectedRevision) {}
    public record Metadata(String name, String domain, Integer expectedRevision) {}
    public record WorkspacePollingSettings(Boolean enabled, Integer intervalSeconds, Integer expectedRevision) {}

    @GetMapping("/source-polling")
    public WorkspacePolling workspacePolling(@PathVariable String workspaceId) {
        return sources.workspacePolling(workspaceId);
    }

    @PatchMapping("/source-polling")
    public WorkspacePolling workspacePolling(@PathVariable String workspaceId, @RequestBody WorkspacePollingSettings request) {
        return sources.configureWorkspacePolling(workspaceId, request.enabled(), request.intervalSeconds(), request.expectedRevision());
    }

    @PatchMapping("/sources/{id}")
    public Source edit(@PathVariable String workspaceId, @PathVariable String id, @RequestBody Metadata request) {
        Source source = sources.editMetadata(workspaceId, id, request.name(), request.domain(), request.expectedRevision());
        search.refresh();
        return source;
    }

    @PatchMapping("/sources/{id}/polling")
    public Source polling(@PathVariable String workspaceId, @PathVariable String id, @RequestBody PollingSettings request) {
        if (Boolean.TRUE.equals(request.enabled())) requireSupportedSource(workspaceId, id);
        return sources.configurePolling(workspaceId, id, request.enabled(), request.intervalSeconds(), request.expectedRevision());
    }

    @GetMapping("/sources")
    public List<Source> list(@PathVariable String workspaceId) {
        return sources.list(workspaceId);
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public Source create(@PathVariable String workspaceId, @RequestBody Registration request) {
        requireSupportedKind(request.kind());
        if ("URL".equals(request.kind())) WebSourcePolicy.validate(request.location() == null ? "" : request.location().strip());
        Source source = sources.create(workspaceId, request.name(), request.kind(), request.location(),
                request.domain() == null ? "" : request.domain());
        if (Boolean.TRUE.equals(request.sync())) {
            source = sources.sync(workspaceId, source.id(), search::refresh);
        }
        return source;
    }

    @PostMapping("/sources/{id}/sync")
    public Source sync(@PathVariable String workspaceId, @PathVariable String id) {
        requireSupportedSource(workspaceId, id);
        Source source = sources.sync(workspaceId, id, search::refresh);
        return source;
    }

    @PostMapping("/sources/{id}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public dev.dougle.sources.application.SourceSyncJobs.Progress startSync(@PathVariable String workspaceId, @PathVariable String id) {
        requireSupportedSource(workspaceId, id);
        return syncJobs.start(workspaceId, id);
    }

    @GetMapping("/sources/{id}/sync-jobs/{jobId}")
    public dev.dougle.sources.application.SourceSyncJobs.Progress syncJob(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String jobId) {
        requireSupportedSource(workspaceId, id);
        return syncJobs.get(workspaceId, id, jobId);
    }

    @GetMapping("/sources/{id}/progress")
    public SourceService.Progress progress(@PathVariable String workspaceId, @PathVariable String id) {
        return sources.progress(workspaceId, id);
    }

    @PostMapping("/sources/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String workspaceId, @PathVariable String id) {
        sources.cancel(workspaceId, id);
    }

    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        sources.delete(workspaceId, id);
        search.refresh();
    }

    private void requireSupportedSource(String workspaceId, String id) {
        Source source = sources.list(workspaceId).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new dev.dougle.library.application.LibraryException(404, "자료를 찾을 수 없습니다."));
        requireSupportedKind(source.kind());
        if ("URL".equals(source.kind())) WebSourcePolicy.validate(source.location());
    }

    private void requireSupportedKind(String kind) {
        if (!"LOCAL_FOLDER".equals(kind) && !"MYSQL".equals(kind) && !"URL".equals(kind)) {
            throw new dev.dougle.library.application.LibraryException(400, "GitHub 직접 연결은 지원하지 않습니다. 내려받은 소스코드의 로컬 폴더를 추가하세요. 기존 수집 자료는 유지됩니다.");
        }
    }

    @GetMapping("/search")
    public List<SearchIndex.Hit> search(@PathVariable String workspaceId, @RequestParam String query,
                                       @RequestParam(required = false) List<String> domains,
                                       @RequestParam(defaultValue = "false") boolean includeKnowledge) {
        return search.search(workspaceId, query, domains == null ? List.of() : domains, includeKnowledge);
    }
}
