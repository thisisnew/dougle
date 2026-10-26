package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.InvestigationPlanService;
import dev.knowledgeforge.investigation.application.InvestigationPlanStore;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/investigation-plans")
public class InvestigationPlanController {
    private final InvestigationPlanService service;
    public record Capture(String requestId) {}
    public InvestigationPlanController(InvestigationPlanService service) { this.service = service; }

    @PostMapping("/preview")
    public InvestigationPlanService.Preview preview(@PathVariable String workspaceId, @RequestBody InvestigationPlanService.PreviewRequest request) {
        return service.preview(workspaceId, request);
    }
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InvestigationPlanStore.Job start(@PathVariable String workspaceId, @RequestBody InvestigationPlanService.Start request) {
        return service.start(workspaceId, request);
    }
    @GetMapping("/jobs")
    public List<InvestigationPlanStore.Job> list(@PathVariable String workspaceId, @RequestParam String bundleId) {
        return service.list(workspaceId, bundleId);
    }
    @GetMapping("/jobs/{id}")
    public InvestigationPlanStore.Job get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @GetMapping("/jobs/{id}/tool-proposal")
    public dev.knowledgeforge.investigation.application.OperationalToolProposal proposal(@PathVariable String workspaceId, @PathVariable String id) {
        return service.proposal(workspaceId, id);
    }
    @PostMapping("/jobs/{id}/cancel")
    public InvestigationPlanStore.Job cancel(@PathVariable String workspaceId, @PathVariable String id) { return service.cancel(workspaceId, id); }
    @PostMapping("/jobs/{id}/capture")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceBundle capture(@PathVariable String workspaceId, @PathVariable String id, @RequestBody Capture request) {
        return service.capture(workspaceId, id, request.requestId());
    }
}
