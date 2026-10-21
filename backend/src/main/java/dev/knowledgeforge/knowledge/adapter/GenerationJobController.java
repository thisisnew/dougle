package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.GenerationJobService;
import dev.knowledgeforge.knowledge.domain.GenerationJob;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class GenerationJobController {
    private final GenerationJobService service;
    public GenerationJobController(GenerationJobService service) { this.service = service; }

    @PostMapping("/knowledge/{cardId}/generation/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationJob submit(@PathVariable String workspaceId, @PathVariable String cardId,
                                @RequestBody GenerationJobService.Request request) {
        return service.submit(workspaceId, cardId, request);
    }

    @GetMapping("/generation-jobs")
    public List<GenerationJob> list(@PathVariable String workspaceId) { return service.list(workspaceId); }

    @GetMapping("/generation-jobs/{id}")
    public GenerationJob get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }

    @PostMapping("/generation-jobs/{id}/cancel")
    public GenerationJob cancel(@PathVariable String workspaceId, @PathVariable String id) { return service.cancel(workspaceId, id); }
}
