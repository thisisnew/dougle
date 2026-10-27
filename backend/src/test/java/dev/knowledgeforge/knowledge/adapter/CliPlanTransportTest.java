package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.investigation.adapter.CliPlanModel;
import dev.knowledgeforge.investigation.application.PlanDecisionParser;
import dev.knowledgeforge.investigation.domain.PlanDecision;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.ProviderExecutionException;
import dev.knowledgeforge.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisabledOnOs(OS.WINDOWS)
class CliPlanTransportTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Provider.class)
    void explicitPlanRouteUsesFixedSchemaAndSharedRestrictions(Provider provider) throws Exception {
        Path schema = directory.resolve("schema.json");
        Path arguments = directory.resolve("args.txt");
        Path prompt = directory.resolve("prompt.txt");
        var model = fixture("""
                printf '%%s\n' "$@" > '%s'
                output=''
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --output-schema) shift; cat "$1" > '%s' ;;
                    --json-schema) shift; printf '%%s' "$1" > '%s' ;;
                    --output-last-message) shift; output="$1" ;;
                  esac
                  shift
                done
                cat > '%s'
                if [ -n "$output" ]; then
                  printf '%%s' '{"decision":"SEARCH","query":"synthetic"}' > "$output"
                else
                  printf '%%s' '{"type":"result","subtype":"success","is_error":false,"structured_output":{"decision":"SEARCH","query":"synthetic"}}'
                fi
                """.formatted(arguments, schema, schema, prompt), Duration.ofSeconds(5));
        String output = new CliPlanModel(model).generate(provider.name(), "ordinary synthetic prompt", "selected-model");
        assertThat(new PlanDecisionParser().parse(output)).isEqualTo(new PlanDecision.Search("synthetic"));
        assertThat(Files.readString(prompt)).isEqualTo("ordinary synthetic prompt");
        assertThat(Files.readString(schema)).isEqualTo(CliCuratorModel.PLAN_SCHEMA);
        var parsed = JsonMapper.builder().build().readTree(Files.readString(schema));
        assertThat(parsed.path("type").asString()).isEqualTo("object");
        assertThat(parsed.path("additionalProperties").asBoolean()).isFalse();
        assertThat(parsed.path("required").toString()).isEqualTo("[\"decision\",\"query\",\"candidate\"]");
        assertThat(parsed.path("properties").path("candidate").path("anyOf").size()).isEqualTo(2);
        assertThat(parsed.path("properties").path("query").path("type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(parsed.path("properties").path("decision").path("enum").toString())
                .isEqualTo("[\"SEARCH\",\"STOP\",\"PROPOSE_MYSQL_READ\"]");
        assertThat(parsed.has("anyOf") || parsed.has("oneOf")).isFalse();
        var flags = Files.readAllLines(arguments);
        assertThat(flags).containsSequence("--model", "selected-model");
        if (provider == Provider.CODEX) {
            assertThat(flags).contains("--json", "--ignore-user-config", "--ephemeral", "shell_tool", "hooks")
                    .containsSequence("--sandbox", "read-only").contains("web_search=\"disabled\"");
        } else {
            assertThat(flags).containsSequence("--tools", "").contains("--strict-mcp-config", "--no-session-persistence")
                    .containsSequence("--permission-mode", "dontAsk");
        }
        model.generate(provider, "Return SEARCH or STOP; this is not a planning transport call", "selected-model");
        assertThat(Files.readString(schema)).isEqualTo(CliCuratorModel.SCHEMA);
    }

    @Test
    void timeoutIncludesOnlySafeExecProgressAndTransportSignal() throws Exception {
        var model = fixture("""
                cat >/dev/null
                printf '%s\n' '{"type":"thread.started","thread_id":"synthetic-secret"}' '{"type":"turn.started"}'
                printf '%s\n' 'ERROR: unexpected status 429 Too Many Requests: Authorization synthetic-secret SELECT private' >&2
                sleep 30
                """, Duration.ofSeconds(2));
        assertThatThrownBy(() -> model.generatePlan(Provider.CODEX, "private prompt", ""))
                .isInstanceOfSatisfying(ProviderExecutionException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.diagnostic().phase()).isEqualTo(ProviderExecutionException.Phase.TURN_STARTED);
                    assertThat(error.diagnostic().execStdinWritten()).isTrue();
                    assertThat(error.diagnostic().stderrSignal()).isEqualTo(ProviderExecutionException.Signal.RATE_LIMIT);
                    assertThat(error.getMessage()).doesNotContain("synthetic-secret", "Authorization", "SELECT", "private", "prompt");
                });
    }

    @Test
    void unsupportedTransportFailsClosedAndAdapterNeverCallsAnswerGeneration() {
        CuratorModel unsupported = (provider, prompt, requestedModel) -> {
            throw new AssertionError("Answer generation must not be used");
        };
        assertThatThrownBy(() -> new CliPlanModel(unsupported).generate("CODEX", "synthetic", ""))
                .isInstanceOf(LibraryException.class);
        CuratorModel transport = mock(CuratorModel.class);
        when(transport.generatePlan(Provider.CLAUDE, "synthetic", "selected")).thenReturn("{\"decision\":\"STOP\",\"query\":null}");
        assertThat(new CliPlanModel(transport).generate("CLAUDE", "synthetic", "selected")).contains("STOP");
        verify(transport).generatePlan(Provider.CLAUDE, "synthetic", "selected");
        verifyNoMoreInteractions(transport);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void planningRetainsTimeoutAndRedactsFailures(Provider provider) throws Exception {
        var model = fixture("cat >/dev/null\nsleep 30\n", Duration.ofMillis(200));
        assertThatThrownBy(() -> model.generatePlan(provider, "synthetic", ""))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(504));
        var failing = fixture("echo private-marker >&2\nexit 9\n", Duration.ofSeconds(5));
        assertThatThrownBy(() -> failing.generatePlan(provider, "synthetic", ""))
                .isInstanceOf(LibraryException.class).hasMessageNotContaining("private-marker");
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void planningInterruptionKillsProcessAndCleansTemporaryDirectory(Provider provider) throws Exception {
        Path started = directory.resolve("started");
        var model = fixture("cat >/dev/null\nprintf '%s\\n' \"$$\" \"$PWD\" > '" + started + "'\nexec sleep 30\n", Duration.ofSeconds(30));
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { model.generatePlan(provider, "synthetic", ""); }
            catch (Throwable exception) { failure.set(exception); }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((!Files.exists(started) || Files.readAllLines(started).size() < 2) && System.nanoTime() < deadline) Thread.sleep(10);
            var details = Files.readAllLines(started);
            worker.interrupt();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(LibraryException.class);
            assertThat(ProcessHandle.of(Long.parseLong(details.get(0))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(Files.exists(Path.of(details.get(1)))).isFalse();
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    private CliCuratorModel fixture(String script, Duration timeout) throws Exception {
        Path executable = directory.resolve("fake-cli");
        Files.writeString(executable, "#!/bin/sh\n" + script);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return new CliCuratorModel(executable.toString(), executable.toString(), timeout);
    }
}
