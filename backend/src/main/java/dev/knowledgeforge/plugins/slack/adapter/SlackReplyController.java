package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackReplyService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/slack/workspaces/{workspaceId}/replies")
public class SlackReplyController {
    private final SlackReplyService service;
    public SlackReplyController(SlackReplyService service) { this.service = service; }
    @PostMapping("/preview") public SlackReplyService.View preview(@PathVariable String workspaceId, @RequestBody(required = false) String body) {
        throw SlackReplyService.readOnly();
    }
    @PostMapping("/{id}/execute") public SlackReplyService.View execute(@PathVariable String workspaceId, @PathVariable String id, @RequestBody(required = false) String body) {
        throw SlackReplyService.readOnly();
    }
    @PostMapping("/{id}/cancel") public SlackReplyService.View cancel(@PathVariable String workspaceId, @PathVariable String id, @RequestBody(required = false) String body) {
        SlackReplyInputs.cancel(body); return service.cancel(workspaceId, id);
    }
    @PostMapping("/{id}/acknowledge-uncertain") public SlackReplyService.View acknowledge(@PathVariable String workspaceId, @PathVariable String id, @RequestBody(required = false) String body) {
        throw SlackReplyService.readOnly();
    }
    @GetMapping("/{id}") public SlackReplyService.View get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @GetMapping public List<SlackReplyService.View> list(@PathVariable String workspaceId, @RequestParam String inboxItemId) { return service.list(workspaceId, inboxItemId); }
}
