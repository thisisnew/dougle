package dev.knowledgeforge.search.adapter;

import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/evidence-bundles")
public class EvidenceBundleController {
    private final EvidenceBundleService service;
    public EvidenceBundleController(EvidenceBundleService service) { this.service = service; }
    public record Create(String question, List<String> domains, Boolean includeKnowledge) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceBundle create(@PathVariable String workspaceId, @RequestBody Create request) {
        return service.create(workspaceId, request.question(), request.domains() == null ? List.of() : request.domains(),
                !Boolean.FALSE.equals(request.includeKnowledge()));
    }

    @GetMapping
    public List<EvidenceBundle.Summary> list(@PathVariable String workspaceId) { return service.list(workspaceId); }

    @GetMapping("/{id}")
    public EvidenceBundle get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) { service.delete(workspaceId, id); }
}
