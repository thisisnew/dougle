package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.ConversationService;
import dev.knowledgeforge.conversation.domain.Conversation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/conversations")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service = service; }
    public record Create(String title) {}
    public record Append(String jobId, Integer expectedRevision) {}
    @GetMapping
    public List<Conversation> list(@PathVariable String workspaceId) { return service.list(workspaceId); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@PathVariable String workspaceId, @RequestBody Create request) { return service.create(workspaceId, request.title()); }
    @GetMapping("/{id}")
    public ConversationService.Detail get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @PostMapping("/{id}/turns")
    public ConversationService.Detail append(@PathVariable String workspaceId, @PathVariable String id, @RequestBody Append request) {
        return service.append(workspaceId, id, request.jobId(), request.expectedRevision());
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) { service.delete(workspaceId, id); }
}
