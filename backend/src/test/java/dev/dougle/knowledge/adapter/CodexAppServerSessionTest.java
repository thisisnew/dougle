package dev.dougle.knowledge.adapter;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CodexAppServerSessionTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final StringWriter output = new StringWriter();
    private final CodexAppServerSession session = new CodexAppServerSession(output, Path.of("/synthetic"), "private-prompt", "model", text -> {});

    private String response(int id, Map<String, ?> value) { return mapper.writeValueAsString(Map.of("id", id, "result", value)); }

    private Map<String, Object> config() {
        var features = new HashMap<String, Boolean>();
        CodexAppServer.DISABLED_FEATURES.forEach(feature -> features.put(feature, false));
        return new HashMap<>(Map.of("features", features, "mcp_servers", Map.of(), "model_providers", Map.of(),
                "approval_policy", "never", "sandbox_mode", "read-only", "web_search", "disabled", "model_provider", "openai"));
    }

    private void initialize() throws Exception {
        session.initialize(); session.accept(response(1, Map.of()));
    }

    private void configuration(Map<String, Object> config) throws Exception {
        session.accept(response(2, Map.of("config", config, "layers", List.of())));
    }

    private Map<String, Object> thread() {
        return new HashMap<>(Map.of("thread", Map.of("id", "thread", "ephemeral", true), "cwd", "/synthetic", "model", "model",
                "modelProvider", "openai", "approvalPolicy", "never", "sandbox", Map.of("type", "readOnly"), "instructionSources", List.of()));
    }

    @Test
    void turnAcceptanceAndInvalidResponsesHaveContentFreeDiagnostics() throws Exception {
        var diagnostics = new ProcessDiagnostics(dev.dougle.knowledge.application.ProviderExecutionException.Transport.CODEX_APP_SERVER);
        var observed = new CodexAppServerSession(new StringWriter(), Path.of("/synthetic"), "private-prompt", "model", ignored -> {}, List.of(), diagnostics);
        observed.initialize();
        observed.accept(response(1, Map.of()));
        observed.accept(response(2, Map.of("config", config(), "layers", List.of())));
        observed.accept(response(3, thread()));
        assertThat(diagnostics.snapshot().appStream().turnAccepted()).isFalse();
        observed.accept(response(4, Map.of("turn", Map.of("id", "private-turn"))));
        assertThat(diagnostics.snapshot().appStream().turnAccepted()).isTrue();
        assertThatThrownBy(() -> observed.accept(response(99, Map.of("secret", "private-value")))).isInstanceOf(IOException.class);
        assertThat(diagnostics.snapshot().appStream().protocolInvalid()).isEqualTo(1);
        assertThat(diagnostics.snapshot().safeSummary()).doesNotContain("private", "/synthetic", "model");
    }

    @Test
    void sendsPromptOnlyAfterConfigurationAndThreadValidation() throws Exception {
        initialize(); configuration(config());
        assertThat(output.toString()).contains("initialized", "config/read", "thread/start").doesNotContain("private-prompt");
        session.accept(response(3, thread()));
        assertThat(output.toString()).contains("turn/start", "private-prompt", "outputSchema");
    }

    @Test
    void rejectsMissingFeatureEvidenceBeforeThreadStart() throws Exception {
        initialize(); var config = config(); config.remove("features");
        assertThatThrownBy(() -> configuration(config)).isInstanceOf(IOException.class);
        assertThat(output.toString()).doesNotContain("thread/start", "private-prompt");
    }

    @Test
    void rejectsMcpEvenIfConfigLayersClaimEmpty() throws Exception {
        initialize(); var config = config(); config.put("mcp_servers", Map.of("synthetic-server", Map.of()));
        assertThatThrownBy(() -> configuration(config)).isInstanceOf(IOException.class);
        assertThat(output.toString()).doesNotContain("private-prompt");
    }

    @Test
    void rejectsLoadedInstructionsAndUnexpectedModel() throws Exception {
        initialize(); configuration(config()); var thread = thread(); thread.put("instructionSources", List.of("/synthetic/AGENTS.md"));
        assertThatThrownBy(() -> session.accept(response(3, thread))).isInstanceOf(IOException.class);
        thread.put("instructionSources", List.of()); thread.put("model", "different-model");
        assertThatThrownBy(() -> session.accept(response(3, thread))).isInstanceOf(IOException.class);
        assertThat(output.toString()).doesNotContain("private-prompt");
    }

    @Test
    void requiresBooleanNetworkAndEphemeralPolicies() throws Exception {
        initialize(); configuration(config()); var thread = thread(); thread.put("sandbox", Map.of("type", "readOnly", "networkAccess", "false"));
        assertThatThrownBy(() -> session.accept(response(3, thread))).isInstanceOf(IOException.class);
        thread.put("sandbox", Map.of("type", "readOnly")); thread.put("thread", Map.of("id", "thread", "ephemeral", "true"));
        assertThatThrownBy(() -> session.accept(response(3, thread))).isInstanceOf(IOException.class);
    }

    @Test
    void capsEarlyNotificationsWhileWaitingForTurnId() throws Exception {
        initialize(); configuration(config()); session.accept(response(3, thread()));
        String event = "{\"method\":\"thread/status/changed\",\"params\":{\"threadId\":\"thread\"}}";
        for (int count = 0; count < 128; count++) session.accept(event);
        assertThatThrownBy(() -> session.accept(event)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsUnscopedErrorsAndServerRequestsBeforePrompt() throws Exception {
        initialize();
        assertThatThrownBy(() -> session.accept("{\"method\":\"error\",\"params\":{}}")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> session.accept("{\"id\":2,\"method\":\"item/tool/call\",\"params\":{}}")).isInstanceOf(IOException.class);
        assertThat(output.toString()).doesNotContain("private-prompt");
    }
}
