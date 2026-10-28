package dev.dougle.conversation.adapter;

import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/chat-threads")
public class ChatThreadController {
    private final ChatThreadService service;
    public ChatThreadController(ChatThreadService service) { this.service = service; }
    @GetMapping
    public List<ChatThreadService.Summary> list(@PathVariable String workspaceId) { return service.list(workspaceId); }
    @GetMapping("/{id}")
    public ChatThreadService.Detail get(@PathVariable String workspaceId, @PathVariable String id) { return service.get(workspaceId, id); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceBundle create(@PathVariable String workspaceId, @RequestBody ChatThreadService.Create request) { return service.create(workspaceId, request); }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String workspaceId, @PathVariable String id) { service.delete(workspaceId, id); }
}
