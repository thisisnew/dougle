package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.domain.SlackEvent;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class SlackEnvelopeParser {
    static final int MAX_BYTES = 65536;
    record Envelope(String type, String envelopeId, SlackEvent event, String teamId, String userId) {
        Envelope(String type, String envelopeId, SlackEvent event) { this(type, envelopeId, event, null, null); }
    }
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static Envelope parse(String text) {
        try {
            if (text == null || text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException();
            var root = JSON.readTree(text);
            if (root == null || !root.isObject() || !root.path("type").isString()) throw new IllegalArgumentException();
            String type = root.path("type").asString();
            if (type.equals("hello") || type.equals("disconnect")) return new Envelope(type, null, null);
            String envelopeId = root.path("envelope_id").asString("");
            if (!envelopeId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
            if (!type.equals("events_api")) return new Envelope(type, envelopeId, null);
            var payload = root.path("payload");
            var event = payload.path("event");
            if (!payload.path("type").asString("").equals("event_callback")) return new Envelope(type, envelopeId, null);
            String eventType = event.path("type").asString("");
            if (java.util.Set.of("tokens_revoked", "app_uninstalled", "member_left_channel").contains(eventType)
                    && payload.path("team_id").asString("").matches("T[A-Z0-9]{1,63}")) {
                String user = eventType.equals("member_left_channel") ? event.path("user").asString("") : null;
                return new Envelope("access_revoked", envelopeId, null, payload.path("team_id").asString(), user);
            }
            String subtype = event.path("subtype").asString("");
            boolean changed = subtype.equals("message_changed");
            boolean deleted = subtype.equals("message_deleted");
            var message = changed ? event.path("message") : deleted ? event.path("previous_message") : event;
            if (!java.util.Set.of("message", "app_mention").contains(eventType)
                    || (!subtype.isEmpty() && !changed && !deleted) || message.hasNonNull("bot_id")
                    || message.hasNonNull("subtype") || (!changed && !deleted && event.path("hidden").asBoolean(false))) {
                return new Envelope(type, envelopeId, null);
            }
            if ((!deleted && (!message.path("text").isString() || !message.path("user").isString() || !message.path("ts").isString()))
                    || deleted && !event.path("deleted_ts").isString() || !event.path("channel").isString()
                    || !payload.path("team_id").isString() || !payload.path("event_id").isString()) {
                return new Envelope(type, envelopeId, null);
            }
            try {
                String timestamp = deleted ? event.path("deleted_ts").asString() : message.path("ts").asString();
                return new Envelope(type, envelopeId, new SlackEvent(payload.path("event_id").asString(),
                        payload.path("team_id").asString(), event.path("channel").asString(), message.path("user").asString(null),
                        timestamp, deleted ? "" : message.path("text").asString(),
                        message.path("thread_ts").asString(timestamp),
                        deleted ? event.path("event_ts").asString(event.path("ts").asString(""))
                                : changed ? message.path("edited").path("ts").asString(event.path("event_ts").asString(""))
                                : message.path("ts").asString(), deleted));
            } catch (IllegalArgumentException invalidEvent) { return new Envelope(type, envelopeId, null); }
        } catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid Slack envelope"); }
    }
}
