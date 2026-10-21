package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.AnswerApprovalService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/evidence-bundles/{bundleId}/answer-approval")
public class AnswerApprovalController {
    private final AnswerApprovalService service;
    public AnswerApprovalController(AnswerApprovalService service) { this.service = service; }
    public record Approve(String provider, String requestedModel, String digest, Boolean acknowledged, String expectedApprovalId) {}

    @GetMapping
    public AnswerApprovalService.State get(@PathVariable String workspaceId, @PathVariable String bundleId) {
        return service.get(workspaceId, bundleId);
    }

    @PostMapping
    public AnswerApprovalService.State approve(@PathVariable String workspaceId, @PathVariable String bundleId, @RequestBody Approve request) {
        return service.approve(workspaceId, bundleId, request.provider(), request.requestedModel(), request.digest(),
                Boolean.TRUE.equals(request.acknowledged()), request.expectedApprovalId());
    }

    @DeleteMapping("/{approvalId}")
    public AnswerApprovalService.State revoke(@PathVariable String workspaceId, @PathVariable String bundleId, @PathVariable String approvalId) {
        return service.revoke(workspaceId, bundleId, approvalId);
    }
}
