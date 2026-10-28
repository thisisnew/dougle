package dev.dougle.search.adapter;

import dev.dougle.search.application.RelationSearch;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/relations")
public class RelationController {
    private final RelationSearch search;
    public RelationController(RelationSearch search) { this.search = search; }

    @GetMapping
    public RelationSearch.Result find(@PathVariable String workspaceId, @RequestParam String kind,
                                      @RequestParam String id, @RequestParam(required = false) Integer version,
                                      @RequestParam(required = false) List<String> domains) {
        return search.find(workspaceId, kind, id, version, domains == null ? List.of() : domains);
    }
}
