package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.BrowserCaptureTransport;
import dev.dougle.investigation.application.BrowserInvestigationService;
import dev.dougle.investigation.application.BrowserInvestigationStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/browser-investigations")
public class BrowserInvestigationController {
    private final BrowserInvestigationService service;
    public BrowserInvestigationController(BrowserInvestigationService service) { this.service = service; }
    @GetMapping("/capability")
    public BrowserCaptureTransport.Capability capability() { return service.capability(); }
    @PostMapping("/preview")
    public BrowserInvestigationService.Preview preview(@PathVariable String workspaceId, @RequestBody BrowserInvestigationService.Request request) {
        return service.preview(workspaceId, request);
    }
    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserInvestigationStore.Job start(@PathVariable String workspaceId, @RequestBody BrowserInvestigationService.Start request) {
        return service.start(workspaceId, request);
    }
    @GetMapping("/jobs")
    public List<BrowserInvestigationStore.Job> list(@PathVariable String workspaceId, @RequestParam String bundleId) {
        return service.list(workspaceId, bundleId);
    }
    @GetMapping("/jobs/{id}")
    public BrowserInvestigationStore.Job get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @PostMapping("/jobs/{id}/cancel")
    public BrowserInvestigationStore.Job cancel(@PathVariable String workspaceId, @PathVariable String id) { return service.cancel(workspaceId, id); }
}
