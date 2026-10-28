package dev.dougle.conversation.adapter;

import dev.dougle.conversation.application.ConversationRequestService;
import dev.dougle.conversation.domain.ConversationRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations/{id}/requests")
public class ConversationRequestController {
    private final ConversationRequestService service;
    public ConversationRequestController(ConversationRequestService service) { this.service = service; }
    @PostMapping
    public ConversationRequest freeze(@PathVariable String workspaceId, @PathVariable String id,
                                      @RequestBody ConversationRequestService.Freeze request) {
        return service.freeze(workspaceId, id, request);
    }
}
