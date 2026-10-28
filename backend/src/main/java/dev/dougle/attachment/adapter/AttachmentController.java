package dev.dougle.attachment.adapter;

import dev.dougle.attachment.application.AttachmentService;
import dev.dougle.attachment.domain.QuestionAttachment;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/attachments")
public class AttachmentController {
    private final AttachmentService service;
    public AttachmentController(AttachmentService service) { this.service = service; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionAttachment.Summary upload(@PathVariable String workspaceId, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > AttachmentService.MAX_FILE_BYTES) throw new dev.dougle.library.application.LibraryException(413, "첨부 파일은 각각 5 MiB 이하여야 합니다.");
        return service.upload(workspaceId, file.getOriginalFilename(), file.getBytes());
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) { service.delete(workspaceId, id); }
}
