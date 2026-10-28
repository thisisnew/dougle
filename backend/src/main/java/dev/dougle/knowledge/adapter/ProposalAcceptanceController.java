package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.ProposalAcceptanceService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/generation-jobs/{jobId}/acceptance")
public class ProposalAcceptanceController {
    private final ProposalAcceptanceService service;

    public ProposalAcceptanceController(ProposalAcceptanceService service) { this.service = service; }

    public record Request(int expectedRevision, int expectedSourceVersion, int expectedResultVersion, String note, boolean confirmed) {}
    public record MergeRequest(int expectedRevision, int expectedSourceVersion, int expectedResultVersion,
                               String note, boolean confirmed, String title, String markdown, Boolean combineEvidence) {}

    @GetMapping("/merge-preview")
    public ProposalAcceptanceService.MergePreview preview(@PathVariable String workspaceId, @PathVariable String jobId) {
        return service.preview(workspaceId, jobId);
    }

    @PostMapping("/merge")
    public ProposalAcceptanceService.State merge(@PathVariable String workspaceId, @PathVariable String jobId, @RequestBody MergeRequest request) {
        return service.merge(workspaceId, jobId, request.expectedRevision(), request.expectedSourceVersion(),
                request.expectedResultVersion(), request.note(), request.confirmed(), request.title(), request.markdown(), Boolean.TRUE.equals(request.combineEvidence()));
    }

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
