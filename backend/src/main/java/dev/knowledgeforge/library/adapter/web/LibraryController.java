package dev.knowledgeforge.library.adapter.web;

import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.library.domain.Entry;
import dev.knowledgeforge.library.domain.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class LibraryController {
    private final LibraryService service;

    public LibraryController(LibraryService service) {
        this.service = service;
    }

    public record NameRequest(@NotBlank String name, String parentId) {}

    @GetMapping("/session")
    public LibraryService.Session session() {
        return service.session();
    }

    @PostMapping("/workspaces")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Workspace createWorkspace(@Valid @RequestBody NameRequest request) {
        return service.createWorkspace(request.name());
    }

    @PutMapping("/workspaces/{id}/active")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void activate(@PathVariable String id) {
        service.activate(id);
    }

    @DeleteMapping("/workspaces/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteWorkspace(@PathVariable String id) {
        service.deleteWorkspace(id);
    }

    @GetMapping("/workspaces/{workspaceId}/entries")
    public List<Entry> entries(@PathVariable String workspaceId,
                              @RequestParam(defaultValue = "ORIGINAL") String area,
                              @RequestParam(required = false) String parentId) {
        return service.entries(workspaceId, area, parentId);
    }

    @PostMapping("/workspaces/{workspaceId}/folders")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Entry createFolder(@PathVariable String workspaceId, @Valid @RequestBody NameRequest request) {
        return service.createFolder(workspaceId, request.parentId(), request.name());
    }

    @PostMapping(value = "/workspaces/{workspaceId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Entry upload(@PathVariable String workspaceId, @RequestParam(required = false) String parentId,
                        @RequestParam MultipartFile file) throws IOException {
        return service.upload(workspaceId, parentId, file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/workspaces/{workspaceId}/entries/{id}/preview")
    public LibraryService.Preview preview(@PathVariable String workspaceId, @PathVariable String id) {
        return service.preview(workspaceId, id);
    }

    @GetMapping("/workspaces/{workspaceId}/entries/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String workspaceId, @PathVariable String id) {
        Entry entry = service.requireEntry(workspaceId, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(entry.name(), StandardCharsets.UTF_8).build().toString())
                .body(service.content(workspaceId, id));
    }

    @DeleteMapping("/workspaces/{workspaceId}/entries/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteEntry(@PathVariable String workspaceId, @PathVariable String id) {
        service.deleteEntry(workspaceId, id);
    }
}
