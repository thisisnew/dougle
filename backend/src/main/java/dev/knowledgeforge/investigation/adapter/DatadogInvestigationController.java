package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/datadog-investigations")
public class DatadogInvestigationController {
    public record Start(String requestId, boolean approved) {}
    private final DatadogInvestigationService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public DatadogInvestigationController(DatadogInvestigationService service) { this.service = service; }

    @PostMapping(value = "/preview", consumes = "application/json")
    public DatadogInvestigationService.Preview preview(@PathVariable String workspaceId, HttpServletRequest request) throws java.io.IOException {
        return service.preview(workspaceId, read(request, DatadogInvestigationService.Query.class));
    }

    @PostMapping(value = "/{id}/execute", consumes = "application/json")
    public DatadogInvestigationService.Result execute(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) throws java.io.IOException {
        Start input = read(request, Start.class);
        return service.execute(workspaceId, id, input.requestId(), input.approved());
    }

    @GetMapping("/{id}")
    public DatadogInvestigationService.Result result(@PathVariable String workspaceId, @PathVariable String id) { return service.result(workspaceId, id); }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String workspaceId, @PathVariable String id) { service.cancel(workspaceId, id); }

    private <Value> Value read(HttpServletRequest request, Class<Value> type) throws java.io.IOException {
        if (request.getContentLengthLong() > 8192) throw new LibraryException(413, "조회 요청이 너무 큽니다.");
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) throw new LibraryException(413, "조회 요청이 너무 큽니다.");
        try {
            Value result = json.readValue(body, type);
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) { throw new LibraryException(400, "Datadog 조회 요청을 확인하세요."); }
    }
}
