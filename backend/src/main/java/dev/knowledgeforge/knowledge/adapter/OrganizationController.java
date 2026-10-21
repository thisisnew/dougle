package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.OrganizationService;
import dev.knowledgeforge.knowledge.domain.Organization.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge-organization")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service = service; }
    public record FolderRequest(String parentId, String name) {}
    public record MoveRequest(String folderId, Integer expectedRevision) {}

    @GetMapping
    public Catalog catalog(@PathVariable String workspaceId) { return service.catalog(workspaceId); }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public Folder create(@PathVariable String workspaceId, @RequestBody FolderRequest request) {
        return service.create(workspaceId, request.parentId(), request.name());
    }

    @DeleteMapping("/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) { service.delete(workspaceId, id); }

    @PutMapping("/cards/{id}")
    public Catalog place(@PathVariable String workspaceId, @PathVariable String id, @RequestBody MoveRequest request) {
        if (request.expectedRevision() == null) throw new dev.knowledgeforge.library.application.LibraryException(400, "기대 위치 revision을 입력하세요.");
        return service.place(workspaceId, id, request.folderId(), request.expectedRevision());
    }
}
