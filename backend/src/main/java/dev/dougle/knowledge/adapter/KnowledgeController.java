package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.KnowledgeService;
import dev.dougle.knowledge.domain.Knowledge.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    public record DraftRequest(String title, String markdown, List<Citation> citations) {}
    public record RevisionRequest(int expectedVersion, String title, String markdown, List<Citation> citations) {}

    @GetMapping
    public List<Card> list(@PathVariable String workspaceId) {
        return service.list(workspaceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Document create(@PathVariable String workspaceId, @RequestBody DraftRequest request) {
        return service.create(workspaceId, request.title(), request.markdown(), request.citations());
    }

    @GetMapping("/{id}")
    public Document get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id, null);
    }

    @GetMapping("/{id}/versions/{number}")
    public Document version(@PathVariable String workspaceId, @PathVariable String id, @PathVariable int number) {
        return service.get(workspaceId, id, number);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Document revise(@PathVariable String workspaceId, @PathVariable String id, @RequestBody RevisionRequest request) {
        return service.revise(workspaceId, id, request.expectedVersion(), request.title(), request.markdown(), request.citations());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) {
        service.delete(workspaceId, id);
    }

    @GetMapping("/{id}/evidence/{snapshotId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String workspaceId, @PathVariable String id, @PathVariable String snapshotId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"evidence-original.bin\"")
                .body(service.snapshotContent(workspaceId, id, snapshotId));
    }

    @GetMapping("/{id}/versions/{number}/evidence/{snapshotId}/location")
    public KnowledgeService.EvidenceLocation location(@PathVariable String workspaceId, @PathVariable String id,
            @PathVariable int number, @PathVariable String snapshotId) {
        return service.locateEvidence(workspaceId, id, number, snapshotId);
    }
}
