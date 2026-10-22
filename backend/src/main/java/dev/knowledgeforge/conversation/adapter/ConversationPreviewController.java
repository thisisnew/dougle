package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.ConversationPreviewService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations/{id}/preview")
public class ConversationPreviewController {
    private final ConversationPreviewService service;
    public ConversationPreviewController(ConversationPreviewService service) { this.service = service; }
    @PostMapping
    public ConversationPreviewService.Preview prepare(@PathVariable String workspaceId, @PathVariable String id,
                                                     @RequestBody ConversationPreviewService.Request request) {
        return service.prepare(workspaceId, id, request);
    }
}
