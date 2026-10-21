package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.GenerationComparisonService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/generation-jobs/{jobId}/comparison")
public class GenerationComparisonController {
    private final GenerationComparisonService service;

    public GenerationComparisonController(GenerationComparisonService service) { this.service = service; }

    @GetMapping
    public GenerationComparisonService.Comparison compare(@PathVariable String workspaceId, @PathVariable String jobId) {
        return service.compare(workspaceId, jobId);
    }
}
