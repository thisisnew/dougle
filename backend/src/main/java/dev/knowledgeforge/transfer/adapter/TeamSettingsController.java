package dev.knowledgeforge.transfer.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.transfer.application.TeamSettingsService;
import dev.knowledgeforge.transfer.application.TeamSettingsService.Descriptor;
import dev.knowledgeforge.transfer.application.TeamSettingsService.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/team-settings")
public class TeamSettingsController {
    public static final int MAX_BYTES = 256 * 1024;
    private final TeamSettingsService service;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public TeamSettingsController(TeamSettingsService service) { this.service = service; }

    @GetMapping(value = "/export/{workspaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Settings exportSettings(@PathVariable String workspaceId) { return service.exportSettings(workspaceId); }

    @PostMapping(value = "/import-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TeamSettingsService.Preview preview(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) throw new LibraryException(413, "팀 설정 파일은 256 KiB 이하여야 합니다.");
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new LibraryException(413, "팀 설정 파일은 256 KiB 이하여야 합니다.");
        Settings settings;
        try {
            var root = object(json.readValue(bytes, Map.class), Set.of("version", "workspaceName", "pollIntervalSeconds", "sources"));
            if (!(root.get("sources") instanceof List<?> items) || items.size() > TeamSettingsService.MAX_SOURCES) throw invalid();
            var descriptors = new ArrayList<Descriptor>();
            for (var item : items) {
                var source = object(item, Set.of("name", "kind", "location"));
                Object location = source.get("location");
                descriptors.add(new Descriptor(string(source.get("name")), string(source.get("kind")), location == null ? null : string(location)));
            }
            settings = new Settings(integer(root.get("version")), string(root.get("workspaceName")),
                    integer(root.get("pollIntervalSeconds")), List.copyOf(descriptors));
        } catch (RuntimeException exception) {
            throw invalid();
        }
        return service.preview(settings);
    }

    private static Map<?, ?> object(Object value, Set<String> fields) {
        if (!(value instanceof Map<?, ?> object) || !object.keySet().equals(fields)) throw invalid();
        return object;
    }

    private static String string(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer number)) throw invalid();
        return number;
    }

    private static LibraryException invalid() { return new LibraryException(400, "팀 설정 JSON의 필드와 형식을 확인하세요."); }
}
