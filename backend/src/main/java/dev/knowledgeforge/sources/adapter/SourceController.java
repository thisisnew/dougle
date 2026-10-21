package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.sources.application.SourceService;
import dev.knowledgeforge.sources.domain.Source;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class SourceController {
    private final SourceService sources;
    private final SearchService search;

    public SourceController(SourceService sources, SearchService search) {
        this.sources = sources;
        this.search = search;
    }

    public record Registration(String name, String kind, String location, String domain) {}

    @GetMapping("/sources")
    public List<Source> list(@PathVariable String workspaceId) {
        return sources.list(workspaceId);
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public Source create(@PathVariable String workspaceId, @RequestBody Registration request) {
        return sources.create(workspaceId, request.name(), request.kind(), request.location(),
                request.domain() == null ? "" : request.domain());
    }

    @PostMapping("/sources/{id}/sync")
    public Source sync(@PathVariable String workspaceId, @PathVariable String id) {
        Source source = sources.sync(workspaceId, id);
        search.refresh();
        return source;
    }

    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        sources.delete(workspaceId, id);
        search.refresh();
    }

    @GetMapping("/search")
    public List<SearchIndex.Hit> search(@PathVariable String workspaceId, @RequestParam String query,
                                       @RequestParam(required = false) List<String> domains,
                                       @RequestParam(defaultValue = "false") boolean includeKnowledge) {
        return search.search(workspaceId, query, domains == null ? List.of() : domains, includeKnowledge);
    }
}
