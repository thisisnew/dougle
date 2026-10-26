package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/plugins/datadog/{workspaceId}")
public class DatadogController {
    private final DatadogConnections connections;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public DatadogController(DatadogConnections connections) { this.connections = connections; }

    @GetMapping
    public DatadogConnections.Status status(@PathVariable String workspaceId) { return connections.status(workspaceId); }

    @PutMapping(consumes = "application/json")
    public DatadogConnections.Status save(@PathVariable String workspaceId, HttpServletRequest request) throws java.io.IOException {
        if (request.getContentLengthLong() > 4096) throw new LibraryException(413, "Datadog 연결 설정이 너무 큽니다.");
        byte[] body = request.getInputStream().readNBytes(4097);
        if (body.length > 4096) throw new LibraryException(413, "Datadog 연결 설정이 너무 큽니다.");
        DatadogLogsClient.Config config;
        try { config = json.readValue(body, DatadogLogsClient.Config.class); }
        catch (RuntimeException failure) { throw new LibraryException(400, "Datadog 연결 설정을 확인하세요."); }
        if (config == null) throw new LibraryException(400, "Datadog 연결 설정을 확인하세요.");
        return connections.save(workspaceId, config);
    }

    @DeleteMapping
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String workspaceId) { connections.disconnect(workspaceId); }
}
