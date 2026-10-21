package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.AnswerJobService;
import dev.knowledgeforge.conversation.domain.AnswerJob;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class AnswerJobController {
    private final AnswerJobService service;
    public AnswerJobController(AnswerJobService service) { this.service = service; }
    @PostMapping("/evidence-bundles/{bundleId}/answer-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AnswerJob submit(@PathVariable String workspaceId, @PathVariable String bundleId, @RequestBody AnswerJobService.Request request) {
        return service.submit(workspaceId, bundleId, request);
    }
    @GetMapping("/answer-jobs")
    public List<AnswerJob> list(@PathVariable String workspaceId) { return service.list(workspaceId); }
    @GetMapping("/answer-jobs/{id}")
    public AnswerJob get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @PostMapping("/answer-jobs/{id}/cancel")
    public AnswerJob cancel(@PathVariable String workspaceId, @PathVariable String id) { return service.cancel(workspaceId, id); }
}
