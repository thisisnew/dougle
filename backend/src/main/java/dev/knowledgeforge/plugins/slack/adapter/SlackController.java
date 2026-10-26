package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackInboxService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/slack")
public class SlackController {
    public record RouteRequest(String workspaceId) {}
    public record SendRequest(String question, boolean startNewConversation, String expectedThreadId) {}
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SlackInboxService service;
    public SlackController(SlackInboxService service) { this.service = service; }
    @GetMapping("/status") public SlackInboxService.Status status() { return service.status(); }
    @PostMapping("/connection") public SlackInboxService.Status connect(@RequestBody SlackInboxService.Credentials request) { return service.connect(request); }
    @DeleteMapping("/connection") public SlackInboxService.Status disconnect() { return service.disconnect(); }
    @GetMapping("/routes") public List<SlackInboxService.Route> routes() { return service.routes(); }
    @PutMapping("/routes/{channelId}") public SlackInboxService.Route route(@PathVariable String channelId, @RequestBody RouteRequest request) { return service.route(channelId, request.workspaceId()); }
    @DeleteMapping("/routes/{teamId}/{channelId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRoute(@PathVariable String teamId, @PathVariable String channelId) { service.removeRoute(teamId, channelId); }
    @GetMapping("/workspaces/{workspaceId}/inbox") public List<SlackInboxService.Item> inbox(@PathVariable String workspaceId) { return service.inbox(workspaceId); }
    @GetMapping("/workspaces/{workspaceId}/inbox-count") public SlackInboxService.Indicator indicator(@PathVariable String workspaceId) { return service.indicator(workspaceId); }
    @PostMapping("/workspaces/{workspaceId}/inbox/{id}/conversation")
    public SlackInboxService.Sent send(@PathVariable String workspaceId, @PathVariable String id, @RequestBody String body) {
        var request = sendRequest(body);
        return service.send(workspaceId, id, request.question(), request.startNewConversation(), request.expectedThreadId());
    }
    static SendRequest sendRequest(String body) {
        try {
            if (body == null || body.length() > 4096 || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException();
            var value = JSON.readTree(body);
            if (value == null || !value.isObject() || !value.path("question").isString()) throw new IllegalArgumentException();
            if (value.size() == 1) return new SendRequest(value.path("question").asString(), false, null);
            if (value.size() != 3 || !value.path("startNewConversation").isBoolean() || !value.path("startNewConversation").asBoolean()
                    || !value.path("expectedThreadId").isString()
                    || !value.path("expectedThreadId").asString().matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
            return new SendRequest(value.path("question").asString(), true, value.path("expectedThreadId").asString());
        } catch (RuntimeException invalid) {
            throw new dev.knowledgeforge.library.application.LibraryException(400, "Slack 대화 저장 JSON 형식이 올바르지 않습니다.");
        }
    }
    @ExceptionHandler(SlackInboxService.MissingThreadException.class)
    public ProblemDetail missingThread(SlackInboxService.MissingThreadException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "SLACK_THREAD_MISSING");
        problem.setProperty("expectedThreadId", exception.expectedThreadId());
        return problem;
    }
    @DeleteMapping("/workspaces/{workspaceId}/inbox/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String workspaceId, @PathVariable String id) { service.remove(workspaceId, id); }
}
