package dev.knowledgeforge.knowledge.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CodexTextStream {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final String threadId;
    private final String turnId;
    private final Consumer<String> consumer;
    private final Set<String> seen = new HashSet<>();
    private final StringBuilder text = new StringBuilder();
    private String itemId;
    private String phase;
    private String candidate;
    private String result;
    private int bytes;
    private boolean failed;

    CodexTextStream(String threadId, String turnId, Consumer<String> consumer) {
        if (threadId == null || threadId.isBlank() || turnId == null || turnId.isBlank() || consumer == null) {
            throw new IllegalArgumentException("Missing stream binding");
        }
        this.threadId = threadId;
        this.turnId = turnId;
        this.consumer = consumer;
    }

    void accept(String line) throws IOException {
        try {
            if (failed || result != null) throw new IOException("Stream already closed");
            int size = line.getBytes(StandardCharsets.UTF_8).length;
            if (size > 262144 || size > 1048576 - bytes) throw new IOException("Stream limit");
            bytes += size;
            if (line.isBlank()) return;
            JsonNode value = mapper.readTree(line);
            if (!value.isObject() || value.has("id") || value.has("error") || value.has("result")) {
                throw new IOException("Unexpected response or server request");
            }
            String method = requiredText(value, "method");
            JsonNode params = value.path("params");
            if (!params.isObject()) throw new IOException("Invalid notification");
            if (!threadId.equals(params.path("threadId").asString(""))) return;
            String eventTurn = method.equals("turn/completed")
                    ? params.path("turn").path("id").asString("") : params.path("turnId").asString("");
            if (!turnId.equals(eventTurn)) return;
            switch (method) {
                case "item/started" -> start(params.path("item"));
                case "item/agentMessage/delta" -> delta(params);
                case "item/completed" -> complete(params.path("item"));
                case "turn/completed" -> finish(params.path("turn"));
                case "error" -> throw new IOException("Turn error");
                default -> { }
            }
        } catch (IOException | RuntimeException exception) {
            failed = true;
            candidate = null;
            result = null;
            text.setLength(0);
            throw new IOException("Invalid Codex stream");
        }
    }

    private void start(JsonNode item) throws IOException {
        if (!requiredText(item, "type").equals("agentMessage")) return;
        String nextId = requiredText(item, "id");
        if (itemId != null || seen.size() >= 64 || !seen.add(nextId)) throw new IOException("Invalid item lifecycle");
        itemId = nextId;
        phase = phase(item);
        text.setLength(0);
        consumer.accept("");
    }

    private void delta(JsonNode params) throws IOException {
        if (itemId == null || !itemId.equals(requiredText(params, "itemId"))) throw new IOException("Unbound delta");
        JsonNode delta = params.path("delta");
        if (!delta.isString()) throw new IOException("Invalid delta");
        if (phase.equals("commentary")) return;
        if (text.length() + delta.asString().length() > 20000) throw new IOException("Partial limit");
        text.append(delta.asString());
        consumer.accept(text.toString());
    }

    private void complete(JsonNode item) throws IOException {
        if (!requiredText(item, "type").equals("agentMessage")) return;
        if (itemId == null || !itemId.equals(requiredText(item, "id"))) throw new IOException("Unbound completed item");
        String completedPhase = phase(item);
        if (!phase.isEmpty() && !phase.equals(completedPhase)) throw new IOException("Changed message phase");
        JsonNode completedText = item.path("text");
        if (!completedText.isString() || completedText.asString().length() > 65536) throw new IOException("Result limit");
        if (completedPhase.isEmpty() || completedPhase.equals("final_answer")) candidate = completedText.asString();
        else candidate = null;
        itemId = null;
        text.setLength(0);
    }

    private void finish(JsonNode turn) throws IOException {
        if (!turn.path("status").asString("").equals("completed")
                || (turn.has("error") && !turn.path("error").isNull()) || itemId != null
                || candidate == null || candidate.isBlank()) throw new IOException("Incomplete answer");
        result = candidate;
        candidate = null;
        consumer.accept("");
    }

    private String phase(JsonNode item) throws IOException {
        JsonNode value = item.path("phase");
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isString() || !Set.of("commentary", "partial_answer", "final_answer").contains(value.asString())) {
            throw new IOException("Invalid message phase");
        }
        return value.asString();
    }

    private String requiredText(JsonNode value, String field) throws IOException {
        JsonNode property = value.path(field);
        if (!property.isString() || property.asString().isBlank() || property.asString().length() > 256) {
            throw new IOException("Invalid identifier");
        }
        return property.asString();
    }

    String result() throws IOException {
        if (failed || result == null) throw new IOException("Missing Codex result");
        return result;
    }
}
