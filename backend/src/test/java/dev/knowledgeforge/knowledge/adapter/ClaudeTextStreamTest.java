package dev.knowledgeforge.knowledge.adapter;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClaudeTextStreamTest {
    static String event(String body) { return "{\"type\":\"stream_event\",\"parent_tool_use_id\":null,\"event\":" + body + "}"; }
    static final String START = event("{\"type\":\"message_start\"}");
    static final String BLOCK = event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}");
    static final String DELTA = event("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"가상\"}}");
    static final String RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"title\":\"fixture\",\"claims\":[]}}";

    @Test
    void accumulatesOnlyTextBeforeIndependentStructuredResult() throws Exception {
        List<String> values = new ArrayList<>(); var stream = new ClaudeTextStream(values::add);
        stream.accept(START); stream.accept(BLOCK); stream.accept(DELTA); stream.accept(DELTA);
        assertThat(values).containsExactly("가상", "가상가상");
        assertThatThrownBy(stream::result).isInstanceOf(java.io.IOException.class);
        stream.accept(RESULT);
        assertThat(stream.result()).contains("claims").doesNotContain("가상");
    }

    @Test
    void excludesThinkingToolArgumentsSubagentsAndUnboundDeltas() throws Exception {
        List<String> values = new ArrayList<>(); var stream = new ClaudeTextStream(values::add);
        stream.accept(DELTA); stream.accept(START); stream.accept(BLOCK.replace("\"text\"", "\"thinking\"")); stream.accept(DELTA);
        stream.accept(BLOCK); stream.accept(DELTA.replace("text_delta", "thinking_delta"));
        stream.accept(DELTA.replace("text_delta", "input_json_delta"));
        stream.accept(DELTA.replace("\"parent_tool_use_id\":null", "\"parent_tool_use_id\":\"child\""));
        stream.accept(event("{\"type\":\"content_block_stop\",\"index\":0}")); stream.accept(DELTA);
        assertThat(values).isEmpty();
    }

    @Test
    void rejectsMissingFailedDuplicateAndMalformedResults() throws Exception {
        var stream = new ClaudeTextStream(value -> {});
        assertThatThrownBy(stream::result).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> stream.accept(RESULT.replace("success", "error"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> stream.accept("[]")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> stream.accept("not-json")).isInstanceOf(RuntimeException.class);
        stream.accept(START);
        assertThatThrownBy(() -> stream.accept(BLOCK.replace("\"index\":0", "\"index\":-1"))).isInstanceOf(java.io.IOException.class);
        stream.accept(RESULT);
        assertThatThrownBy(() -> stream.accept(RESULT)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void capsTemporaryTextBeforeDelivery() throws Exception {
        List<String> values = new ArrayList<>(); var stream = new ClaudeTextStream(values::add);
        stream.accept(START); stream.accept(BLOCK);
        assertThatThrownBy(() -> stream.accept(DELTA.replace("가상", "x".repeat(20001)))).isInstanceOf(java.io.IOException.class);
        assertThat(values).isEmpty();
    }
}
