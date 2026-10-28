package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CuratorService;
import dev.dougle.knowledge.domain.Knowledge.Document;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge/{id}/generation")
public class CuratorController {
    private final CuratorService service;
    public CuratorController(CuratorService service) { this.service = service; }
    public record PreviewRequest(String provider, String requestedModel) {}
    public record GenerateRequest(String provider, int expectedVersion, String digest, boolean approved, String requestedModel) {}

    @PostMapping("/preview")
    public CuratorService.Preview preview(@PathVariable String workspaceId, @PathVariable String id,
                                          @RequestBody PreviewRequest request) {
        return service.preview(workspaceId, id, request.provider(), request.requestedModel());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Document generate(@PathVariable String workspaceId, @PathVariable String id,
                             @RequestBody GenerateRequest request) {
        return service.generate(workspaceId, id, request.provider(), request.expectedVersion(), request.digest(), request.approved(), request.requestedModel());
    }
}
