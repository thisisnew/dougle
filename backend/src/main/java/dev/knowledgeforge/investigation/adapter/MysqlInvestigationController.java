package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.plugins.adapter.MysqlReadClient;
import dev.knowledgeforge.library.application.LibraryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/mysql-investigations")
public class MysqlInvestigationController {
    public record Start(String requestId, boolean approved) {}
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final MysqlInvestigationService service;
    public MysqlInvestigationController(MysqlInvestigationService service) { this.service = service; }
    @PostMapping(value = "/preview", consumes = "application/json")
    public MysqlInvestigationService.Preview preview(@PathVariable String workspaceId, HttpServletRequest request) throws java.io.IOException {
        return service.preview(workspaceId, read(request, MysqlReadClient.Request.class));
    }
    @PostMapping(value = "/{id}/execute", consumes = "application/json")
    public MysqlInvestigationService.Result execute(@PathVariable String workspaceId, @PathVariable String id, HttpServletRequest request) throws java.io.IOException {
        var start = read(request, Start.class);
        return service.execute(workspaceId, id, start.requestId(), start.approved());
    }
    @GetMapping("/{id}")
    public MysqlInvestigationService.Result result(@PathVariable String workspaceId, @PathVariable String id) {
        return service.result(workspaceId, id);
    }
    @PostMapping("/{id}/cancel")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String workspaceId, @PathVariable String id) { service.cancel(workspaceId, id); }

    private <T> T read(HttpServletRequest request, Class<T> type) throws java.io.IOException {
        if (request.getContentLengthLong() > 8192) throw new LibraryException(413, "조회 요청 크기를 줄여 주세요.");
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) throw new LibraryException(413, "조회 요청 크기를 줄여 주세요.");
        try {
            T value = json.readValue(body, type);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new LibraryException(400, "DB 조회 요청 형식을 확인하세요."); }
    }
}
