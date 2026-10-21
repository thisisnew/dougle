package dev.knowledgeforge.knowledge.adapter;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class CodexAppServerSession {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Writer output;
    private final Path directory;
    private final String prompt;
    private final String model;
    private final Consumer<String> text;
    private final List<String> early = new ArrayList<>();
    private int awaiting = 1;
    private String threadId;
    private CodexTextStream stream;
    private String result;

    CodexAppServerSession(Writer output, Path directory, String prompt, String model, Consumer<String> text) {
        this.output = output;
        this.directory = directory;
        this.prompt = prompt;
        this.model = model;
        this.text = text;
    }

    void initialize() throws IOException {
        request("initialize", Map.of("clientInfo", Map.of("name", "knowledge_forge", "version", "0.1.0")));
    }

    void accept(String line) throws IOException {
        JsonNode value = mapper.readTree(line);
        if (!value.isObject() || result != null || value.has("error")) throw new IOException("Invalid response");
        if (value.has("id")) {
            if (value.has("method") || !value.path("id").isIntegralNumber()
                    || value.path("id").asInt(-1) != awaiting || !value.path("result").isObject()) {
                throw new IOException("Unexpected response or server request");
            }
            JsonNode response = value.path("result");
            switch (awaiting) {
                case 1 -> {
                    send(Map.of("method", "initialized", "params", Map.of()));
                    awaiting = 2;
                    request("config/read", Map.of("includeLayers", true, "cwd", directory.toString()));
                }
                case 2 -> {
                    validateConfig(response);
                    awaiting = 3;
                    var params = new HashMap<String, Object>();
                    params.put("cwd", directory.toString()); params.put("ephemeral", true);
                    params.put("approvalPolicy", "never"); params.put("sandbox", "read-only");
                    params.put("modelProvider", "openai");
                    if (!model.isEmpty()) params.put("model", model);
                    request("thread/start", params);
                }
                case 3 -> {
                    validateThread(response);
                    threadId = identifier(response.path("thread").path("id"));
                    awaiting = 4;
                    request("turn/start", Map.of("threadId", threadId, "approvalPolicy", "never",
                            "sandboxPolicy", Map.of("type", "readOnly", "networkAccess", false),
                            "input", List.of(Map.of("type", "text", "text", prompt, "text_elements", List.of())),
                            "outputSchema", mapper.readTree(CliCuratorModel.SCHEMA)));
                }
                case 4 -> {
                    String turnId = identifier(response.path("turn").path("id"));
                    stream = new CodexTextStream(threadId, turnId, text);
                    awaiting = 0;
                    for (String notification : early) notification(notification);
                    early.clear();
                }
                default -> throw new IOException("Unsolicited response");
            }
        } else {
            String method = identifier(value.path("method"));
            if (!value.path("params").isObject() || method.equals("error")) throw new IOException("Server error");
            if (method.equals("item/started")) {
                String type = value.path("params").path("item").path("type").asString("");
                if (!List.of("userMessage", "agentMessage", "reasoning").contains(type)) throw new IOException("Unexpected tool item");
            }
            if (stream != null) notification(line);
            else if (awaiting == 4) {
                if (early.size() >= 128) throw new IOException("Early notification limit");
                early.add(line);
            }
        }
    }

    private void notification(String line) throws IOException {
        stream.accept(line);
        if (stream.completed()) result = stream.result();
    }

    private void validateConfig(JsonNode response) throws IOException {
        JsonNode layers = response.path("layers");
        if (!layers.isArray()) throw new IOException("Missing config layers");
        for (JsonNode layer : layers) {
            if (layer.path("disabledReason").isString() && !layer.path("disabledReason").asString().isBlank()) continue;
            JsonNode config = layer.path("config");
            if (!config.isObject() || (!layer.path("name").path("type").asString("").equals("sessionFlags")
                    && !config.isEmpty())) throw new IOException("External configuration is not allowed");
        }
        JsonNode config = response.path("config");
        for (String feature : CodexAppServer.DISABLED_FEATURES) {
            JsonNode enabled = config.path("features").path(feature);
            if (!enabled.isBoolean() || enabled.asBoolean()) throw new IOException("Unsafe feature configuration");
        }
        if (!config.path("mcp_servers").isObject() || !config.path("mcp_servers").isEmpty()
                || !config.path("model_providers").isObject() || !config.path("model_providers").isEmpty()) {
            throw new IOException("External server configuration");
        }
        if (!config.path("approval_policy").asString("").equals("never")
                || !config.path("sandbox_mode").asString("").equals("read-only")
                || !config.path("web_search").asString("").equals("disabled")
                || !config.path("model_provider").asString("").equals("openai")) throw new IOException("Unsafe configuration");
    }

    private void validateThread(JsonNode response) throws IOException {
        JsonNode sources = response.path("instructionSources");
        JsonNode sandbox = response.path("sandbox");
        if (!response.path("approvalPolicy").asString("").equals("never")
                || !response.path("modelProvider").asString("").equals("openai")
                || !response.path("cwd").asString("").equals(directory.toString())
                || !sandbox.path("type").asString("").equals("readOnly")
                || (sandbox.has("networkAccess") && !sandbox.path("networkAccess").isBoolean())
                || sandbox.path("networkAccess").asBoolean(false)
                || !sources.isArray() || !sources.isEmpty()
                || !response.path("thread").path("ephemeral").isBoolean()
                || !response.path("thread").path("ephemeral").asBoolean(false)
                || (!model.isEmpty() && !model.equals(response.path("model").asString("")))) {
            throw new IOException("Unsafe thread configuration");
        }
    }

    private String identifier(JsonNode value) throws IOException {
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 256) throw new IOException("Invalid identifier");
        return value.asString();
    }

    private void request(String method, Map<String, ?> params) throws IOException {
        send(Map.of("id", awaiting, "method", method, "params", params));
    }

    private void send(Map<String, ?> message) throws IOException {
        output.write(mapper.writeValueAsString(message)); output.write("\n"); output.flush();
    }

    String result() { return result; }
}
