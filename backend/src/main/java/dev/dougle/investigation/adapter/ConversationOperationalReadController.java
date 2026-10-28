package dev.dougle.investigation.adapter;

import dev.dougle.library.application.LibraryException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversation-operational-reads")
public class ConversationOperationalReadController {
    private final ConversationOperationalReadService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public ConversationOperationalReadController(ConversationOperationalReadService service) { this.service = service; }

    @PostMapping(value = "/preview", consumes = "application/json")
    public ConversationOperationalReadService.Preview preview(@PathVariable String workspaceId, HttpServletRequest request) throws IOException {
        return service.preview(workspaceId, read(request, ConversationOperationalReadService.Input.class));
    }

    @PostMapping(value = "/{id}/execute", consumes = "application/json")
    public ConversationOperationalReadService.Result execute(@PathVariable String workspaceId, @PathVariable String id,
                                                              HttpServletRequest request) throws IOException {
        return service.execute(workspaceId, id, read(request, ConversationOperationalReadService.Start.class));
    }

    @GetMapping("/{id}")
    public ConversationOperationalReadService.Result get(@PathVariable String workspaceId, @PathVariable String id) {
        return service.get(workspaceId, id);
    }

    @PostMapping("/{id}/cancel")
    public ConversationOperationalReadService.Result cancel(@PathVariable String workspaceId, @PathVariable String id) {
        return service.cancel(workspaceId, id);
    }

    @PostMapping("/{id}/capture-preview")
    public ConversationOperationalReadService.CapturePreview capturePreview(@PathVariable String workspaceId, @PathVariable String id) {
        return service.capturePreview(workspaceId, id);
    }

    @PostMapping(value = "/{id}/capture", consumes = "application/json")
    public ConversationOperationalReadService.CaptureReceipt capture(@PathVariable String workspaceId, @PathVariable String id,
                                                                     HttpServletRequest request) throws IOException {
        return service.capture(workspaceId, id, read(request, ConversationOperationalReadService.Start.class));
    }

    @GetMapping("/{id}/capture-receipt")
    public ConversationOperationalReadService.CaptureReceipt captureReceipt(@PathVariable String workspaceId, @PathVariable String id) {
        return service.captureReceipt(workspaceId, id);
    }

    private <Value> Value read(HttpServletRequest request, Class<Value> type) throws IOException {
        if (request.getContentLengthLong() > 2048) throw new LibraryException(413, "대화 조회 요청이 너무 큽니다.");
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) throw new LibraryException(413, "대화 조회 요청이 너무 큽니다.");
        try {
            var value = json.readValue(body, type);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new LibraryException(400, "대화 조회 요청 형식을 확인하세요."); }
    }
}
