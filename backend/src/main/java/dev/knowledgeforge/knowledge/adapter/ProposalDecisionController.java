package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.ProposalDecisionService;
import dev.knowledgeforge.knowledge.domain.ProposalDecision;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/generation-jobs/{jobId}/decisions")
public class ProposalDecisionController {
    private final ProposalDecisionService service;

    public ProposalDecisionController(ProposalDecisionService service) { this.service = service; }

    public record Request(int expectedRevision, int expectedSourceVersion, int expectedResultVersion, String status, String note) {}

    @GetMapping
    public List<ProposalDecision> history(@PathVariable String workspaceId, @PathVariable String jobId) {
        return service.history(workspaceId, jobId);
    }

    @PostMapping
    public List<ProposalDecision> decide(@PathVariable String workspaceId, @PathVariable String jobId, @RequestBody Request request) {
        return service.decide(workspaceId, jobId, request.expectedRevision(), request.expectedSourceVersion(),
                request.expectedResultVersion(), request.status(), request.note());
    }
}
