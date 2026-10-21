package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.ProposalAcceptanceService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/generation-jobs/{jobId}/acceptance")
public class ProposalAcceptanceController {
    private final ProposalAcceptanceService service;

    public ProposalAcceptanceController(ProposalAcceptanceService service) { this.service = service; }

    public record Request(int expectedRevision, int expectedSourceVersion, int expectedResultVersion, String note, boolean confirmed) {}

    @GetMapping
    public ProposalAcceptanceService.State state(@PathVariable String workspaceId, @PathVariable String jobId) {
        return service.state(workspaceId, jobId);
    }

    @PostMapping
    public ProposalAcceptanceService.State accept(@PathVariable String workspaceId, @PathVariable String jobId, @RequestBody Request request) {
        return service.accept(workspaceId, jobId, request.expectedRevision(), request.expectedSourceVersion(),
                request.expectedResultVersion(), request.note(), request.confirmed());
    }
}
