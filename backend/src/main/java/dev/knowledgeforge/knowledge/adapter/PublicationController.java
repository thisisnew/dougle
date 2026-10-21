package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.PublicationService;
import dev.knowledgeforge.knowledge.domain.Knowledge.Document;
import dev.knowledgeforge.knowledge.domain.Publication.State;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge/{id}")
public class PublicationController {
    private final PublicationService service;

    public PublicationController(PublicationService service) { this.service = service; }

    public record ReviewRequest(int expectedVersion, String reviewer, String note, boolean claimsSupported, boolean uncertaintyRemoved) {}
    public record PublishRequest(int expectedVersion, int expectedPublicationRevision) {}

    @GetMapping("/publication")
    public State state(@PathVariable String workspaceId, @PathVariable String id) { return service.state(workspaceId, id); }

    @PostMapping("/review")
    public State review(@PathVariable String workspaceId, @PathVariable String id, @RequestBody ReviewRequest request) {
        return service.review(workspaceId, id, request.expectedVersion(), request.reviewer(), request.note(), request.claimsSupported(), request.uncertaintyRemoved());
    }

    @PostMapping("/publication")
    public State publish(@PathVariable String workspaceId, @PathVariable String id, @RequestBody PublishRequest request) {
        return service.publish(workspaceId, id, request.expectedVersion(), request.expectedPublicationRevision());
    }

    @GetMapping("/published")
    public Document published(@PathVariable String workspaceId, @PathVariable String id) { return service.published(workspaceId, id); }
}
