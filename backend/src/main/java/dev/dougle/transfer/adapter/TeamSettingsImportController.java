package dev.dougle.transfer.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.transfer.application.TeamSettingsImportService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/team-settings")
public class TeamSettingsImportController {
    private final TeamSettingsImportService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public TeamSettingsImportController(TeamSettingsImportService service) { this.service = service; }

    @PostMapping(value = "/import-plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TeamSettingsImportService.Plan plan(HttpServletRequest request) throws IOException {
        return service.plan(read(request, TeamSettingsImportService.PlanRequest.class));
    }

    @PostMapping(value = "/import-apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TeamSettingsImportService.Applied apply(HttpServletRequest request) throws IOException {
        return service.apply(read(request, TeamSettingsImportService.ApplyRequest.class));
    }

    private <Value> Value read(HttpServletRequest request, Class<Value> type) throws IOException {
        if (request.getContentLengthLong() > TeamSettingsController.MAX_BYTES) throw new LibraryException(413, "팀 설정 파일은 256 KiB 이하여야 합니다.");
        var bytes = request.getInputStream().readNBytes(TeamSettingsController.MAX_BYTES + 1);
        if (bytes.length > TeamSettingsController.MAX_BYTES) throw new LibraryException(413, "팀 설정 파일은 256 KiB 이하여야 합니다.");
        try { return json.readValue(bytes, type); }
        catch (RuntimeException exception) { throw new LibraryException(400, "팀 설정 요청의 필드와 형식을 확인하세요."); }
    }
}
