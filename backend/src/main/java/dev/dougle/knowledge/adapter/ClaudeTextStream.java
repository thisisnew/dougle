package dev.dougle.knowledge.adapter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

final class ClaudeTextStream {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Consumer<String> consumer;
    private final Set<Integer> textBlocks = new HashSet<>();
    private final StringBuilder text = new StringBuilder();
    private String result;
    private boolean message;

    ClaudeTextStream(Consumer<String> consumer) { this.consumer = consumer; }

    void accept(String line) throws IOException {
        if (line.isBlank()) return;
        var value = mapper.readTree(line);
        if (!value.isObject() || result != null) throw new IOException("Invalid stream envelope");
        if (value.path("type").asString("").equals("result")) {
            if (!value.path("subtype").asString("").equals("success") || value.path("is_error").asBoolean(true)
                    || !value.path("structured_output").isObject()) throw new IOException("Invalid stream result");
            result = value.path("structured_output").toString();
            if (result.length() > 65536) throw new IOException("Result limit");
            return;
        }
        if (!value.path("type").asString("").equals("stream_event")
                || !value.path("parent_tool_use_id").isNull()) return;
        var event = value.path("event");
        String type = event.path("type").asString("");
        if (type.equals("message_start")) {
            message = true; textBlocks.clear();
        } else if (type.equals("message_stop")) {
            message = false; textBlocks.clear();
        } else if (message && type.equals("content_block_start") && event.path("content_block").path("type").asString("").equals("text")) {
            int index = event.path("index").asInt(-1);
            if (index < 0 || index > 1024) throw new IOException("Invalid text block index");
            textBlocks.add(index);
        } else if (type.equals("content_block_stop")) {
            textBlocks.remove(event.path("index").asInt(-1));
        } else if (message && type.equals("content_block_delta") && textBlocks.contains(event.path("index").asInt(-1))
                && event.path("delta").path("type").asString("").equals("text_delta")) {
            var delta = event.path("delta").path("text");
            if (!delta.isString() || text.length() + delta.asString().length() > 20000) throw new IOException("Partial text limit");
            text.append(delta.asString());
            consumer.accept(text.toString());
        }
    }

    String result() throws IOException {
        if (result == null) throw new IOException("Missing stream result");
        return result;
    }
}
