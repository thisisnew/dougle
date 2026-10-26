package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.InvestigationPlanService;
import dev.knowledgeforge.investigation.application.InvestigationPlanStore;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/investigation-plans")
public class InvestigationPlanController {
    private final InvestigationPlanService service;
    private final tools.jackson.databind.json.JsonMapper scopeJson = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public record Capture(String requestId) {}
    public record ScopeCancel(long expectedRevision) {}
    public InvestigationPlanController(InvestigationPlanService service) { this.service = service; }

    @PostMapping("/preview")
    public InvestigationPlanService.Preview preview(@PathVariable String workspaceId, @RequestBody InvestigationPlanService.PreviewRequest request) {
        return service.preview(workspaceId, request);
    }
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InvestigationPlanStore.Job start(@PathVariable String workspaceId, @RequestBody InvestigationPlanService.Start request) {
        return service.start(workspaceId, request);
    }
    @GetMapping("/jobs")
    public List<InvestigationPlanStore.Job> list(@PathVariable String workspaceId, @RequestParam String bundleId) {
        return service.list(workspaceId, bundleId);
    }
    @GetMapping("/jobs/{id}")
    public InvestigationPlanStore.Job get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @GetMapping("/jobs/{id}/tool-proposal")
    public dev.knowledgeforge.investigation.application.OperationalToolProposal proposal(@PathVariable String workspaceId, @PathVariable String id) {
        return service.proposal(workspaceId, id);
    }
    @GetMapping("/jobs/{id}/clarification")
    public dev.knowledgeforge.investigation.application.OperationalClarification clarification(@PathVariable String workspaceId, @PathVariable String id) {
        return service.clarification(workspaceId, id);
    }
    @PostMapping("/jobs/{id}/cancel")
    public InvestigationPlanStore.Job cancel(@PathVariable String workspaceId, @PathVariable String id) { return service.cancel(workspaceId, id); }
    @PostMapping(value = "/jobs/{id}/scope/cancel", consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelScope(@PathVariable String workspaceId, @PathVariable String id,
                            jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        byte[] body = request.getInputStream().readNBytes(513);
        if (body.length > 512) throw new dev.knowledgeforge.library.application.LibraryException(413, "조회 범위 취소 요청이 너무 큽니다.");
        ScopeCancel input;
        try {
            input = scopeJson.readValue(body, ScopeCancel.class);
            if (input == null) throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new dev.knowledgeforge.library.application.LibraryException(400, "조회 범위 취소 요청 형식을 확인하세요.");
        }
        service.cancelScope(workspaceId, id, input.expectedRevision());
    }
    @PostMapping("/jobs/{id}/capture")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceBundle capture(@PathVariable String workspaceId, @PathVariable String id, @RequestBody Capture request) {
        return service.capture(workspaceId, id, request.requestId());
    }
}
