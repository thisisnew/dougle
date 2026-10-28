package dev.dougle.transfer.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.transfer.application.WorkspaceArchiveService;
import dev.dougle.transfer.domain.TransferArchiveCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/workspace-archives")
public class WorkspaceArchiveController {
    private final WorkspaceArchiveService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    public WorkspaceArchiveController(WorkspaceArchiveService service) { this.service = service; }

    @GetMapping("/export/{workspaceId}")
    public ResponseEntity<byte[]> exportArchive(@PathVariable String workspaceId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=workspace-archive.zip")
                .body(service.exportArchive(workspaceId));
    }

    @PostMapping(value = "/import-preview", consumes = {"application/zip", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public WorkspaceArchiveService.Preview preview(@RequestParam String workspaceName, HttpServletRequest request) throws IOException {
        return service.preview(read(request, TransferArchiveCodec.MAX_ARCHIVE_BYTES), workspaceName);
    }

    @PostMapping(value = "/import-apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WorkspaceArchiveService.Result apply(HttpServletRequest request) throws IOException {
        WorkspaceArchiveService.Apply input;
        byte[] bytes = read(request, 4096);
        try { input = json.readValue(bytes, WorkspaceArchiveService.Apply.class); }
        catch (RuntimeException exception) { throw new LibraryException(400, "미리보기 요청 형식을 확인하세요."); }
        return service.apply(input);
    }

    private byte[] read(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw new LibraryException(413, "아카이브 요청 크기 제한을 넘었습니다.");
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new LibraryException(413, "아카이브 요청 크기 제한을 넘었습니다.");
        return bytes;
    }
}
