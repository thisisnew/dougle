package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/operational-captures")
public class OperationalCaptureController {
    private final OperationalCaptureService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public OperationalCaptureController(OperationalCaptureService service) { this.service = service; }

    @PostMapping(value = "/preview", consumes = "application/json")
    public OperationalCaptureService.Preview preview(@PathVariable String workspaceId, HttpServletRequest request) throws java.io.IOException {
        return service.preview(workspaceId, read(request, OperationalCaptureService.Input.class));
    }
    @PostMapping(value = "/{id}/capture", consumes = "application/json")
    public OperationalCaptureService.Receipt capture(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) throws java.io.IOException {
        return service.capture(workspaceId, id, read(request, OperationalCaptureService.Start.class));
    }
    @GetMapping("/{id}")
    public OperationalCaptureService.Receipt get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }

    private <Value> Value read(HttpServletRequest request, Class<Value> type) throws java.io.IOException {
        if (request.getContentLengthLong() > 2048) throw new LibraryException(413, "저장 요청이 너무 큽니다.");
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) throw new LibraryException(413, "저장 요청이 너무 큽니다.");
        try {
            Value value = json.readValue(body, type);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new LibraryException(400, "로컬 저장 요청 형식을 확인하세요."); }
    }
}
