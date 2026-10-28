package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.OrganizationService;
import dev.dougle.knowledge.domain.Organization.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/knowledge-organization")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service = service; }
    public record FolderRequest(String parentId, String name) {}
    public record MoveRequest(String folderId, Integer expectedRevision) {}
    public record UpdateFolderRequest(String parentId, String name, Integer expectedRevision) {}

    @PutMapping("/folders/{id}")
    public Folder update(@PathVariable String workspaceId, @PathVariable String id, @RequestBody UpdateFolderRequest request) {
        if (request.expectedRevision() == null) throw new dev.dougle.library.application.LibraryException(400, "기대 폴더 revision을 입력하세요.");
        return service.update(workspaceId, id, request.parentId(), request.name(), request.expectedRevision());
    }

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
        if (request.expectedRevision() == null) throw new dev.dougle.library.application.LibraryException(400, "기대 위치 revision을 입력하세요.");
        return service.place(workspaceId, id, request.folderId(), request.expectedRevision());
    }
}
