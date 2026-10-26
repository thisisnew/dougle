package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.slack.application.SlackReplyService;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class SlackReplyInputs {
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static SlackReplyService.PreviewInput preview(String raw) {
        var value = object(raw, Set.of("requestId", "inboxItemId", "body"));
        return new SlackReplyService.PreviewInput(string(value, "requestId"), string(value, "inboxItemId"), string(value, "body"));
    }
    static SlackReplyService.ExecuteInput execute(String raw) {
        var value = object(raw, Set.of("requestId", "digest", "approved"));
        if (!value.path("approved").isBoolean() || !value.path("approved").asBoolean()) throw invalid();
        return new SlackReplyService.ExecuteInput(string(value, "requestId"), string(value, "digest"), true);
    }
    static void cancel(String raw) { object(raw, Set.of()); }
    static void acknowledge(String raw) {
        var value = object(raw, Set.of("acknowledged"));
        if (!value.path("acknowledged").isBoolean() || !value.path("acknowledged").asBoolean()) throw invalid();
    }
    private static JsonNode object(String raw, Set<String> fields) {
        try {
            if (raw == null || raw.length() > 32768 || raw.getBytes(StandardCharsets.UTF_8).length > 32768) throw invalid();
            var value = JSON.readTree(raw);
            if (value == null || !value.isObject() || value.size() != fields.size()) throw invalid();
            for (String field : fields) if (!value.has(field)) throw invalid();
            return value;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    private static String string(JsonNode value, String field) { if (!value.path(field).isString()) throw invalid(); return value.path(field).asString(); }
    private static LibraryException invalid() { return new LibraryException(400, "Slack 답장 JSON 형식이 올바르지 않습니다."); }
}
