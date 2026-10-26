package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.ProviderExecutionException.Phase;
import dev.knowledgeforge.knowledge.application.ProviderExecutionException.Signal;
import dev.knowledgeforge.knowledge.application.ProviderExecutionException.Transport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProcessDiagnosticsTest {
    @Test void legacyConstructorAndSummaryRemainCompatible() {
        var diagnostic = new dev.knowledgeforge.knowledge.application.ProviderExecutionException.Diagnostic(
                Transport.CODEX_EXEC, Phase.PROMPT_WRITTEN, Signal.NONE, true, 12, 0);
        assertThat(diagnostic.appStream()).isNull();
        assertThat(diagnostic.safeSummary()).isEqualTo("transport=CODEX_EXEC, phase=PROMPT_WRITTEN, stderrSignal=NONE, execStdinWritten=true, stdoutBytes=12, stderrBytes=0");
    }

    @Test void streamCountersSaturateAndSnapshotsAreImmutable() {
        var diagnostics = new ProcessDiagnostics(Transport.CODEX_APP_SERVER);
        diagnostics.appEvent(ProcessDiagnostics.AppEvent.TURN_ACCEPTED);
        var initial = diagnostics.snapshot();
        for (int index = 0; index < 1048580; index++) diagnostics.appEvent(ProcessDiagnostics.AppEvent.IGNORED_BINDING);
        diagnostics.appPendingLineBytes(Integer.MAX_VALUE);
        diagnostics.appEvent(ProcessDiagnostics.AppEvent.PROTOCOL_INVALID);
        diagnostics.appEvent(ProcessDiagnostics.AppEvent.PROTOCOL_INVALID);
        var current = diagnostics.snapshot().appStream();
        assertThat(initial.appStream().ignoredBindings()).isZero();
        assertThat(current.ignoredBindings()).isEqualTo(1048576);
        assertThat(current.pendingLineBytes()).isEqualTo(262144);
        assertThat(current.protocolInvalid()).isEqualTo(1);
        assertThat(current.turnAccepted()).isTrue();
        assertThat(current.phase()).isEqualTo(dev.knowledgeforge.knowledge.application.ProviderExecutionException.StreamPhase.PROTOCOL_INVALID);
    }

    @Test void splitEventsAdvanceWithoutRetainingPromptIdsOrSql() {
        var diagnostics = new ProcessDiagnostics(Transport.CODEX_EXEC);
        emit(diagnostics, false, "{\"type\":\"thread.sta");
        emit(diagnostics, false, "rted\",\"thread_id\":\"private-id\"}\n{\"type\":\"turn.started\"}\n");
        diagnostics.promptWritten();
        emit(diagnostics, false, "{\"type\":\"item.completed\",\"text\":\"SELECT secret FROM private_table\"}\n");
        assertThat(diagnostics.snapshot().phase()).isEqualTo(Phase.TURN_STARTED);
        assertThat(diagnostics.snapshot().execStdinWritten()).isTrue();
        assertThat(diagnostics.snapshot().stdoutBytes()).isPositive();
        assertThat(diagnostics.snapshot().safeSummary()).doesNotContain("private", "SELECT", "secret");
        emit(diagnostics, false, "{\"type\":\"turn.completed\"}\n");
        assertThat(diagnostics.snapshot().phase()).isEqualTo(Phase.TURN_COMPLETED);
    }

    @Test void onlyPreciseTransportHttpPatternsYieldSignals() {
        var diagnostics = new ProcessDiagnostics(Transport.CODEX_EXEC);
        emit(diagnostics, true, "Authorization: Bearer synthetic-secret\nSELECT 'HTTP 429'\nnetwork error\nrate limit\nHTTP 503\n");
        assertThat(diagnostics.snapshot().stderrSignal()).isEqualTo(Signal.NONE);
        emit(diagnostics, true, "ERROR: unexpected status 4");
        emit(diagnostics, true, "29 Too Many Requests: synthetic-secret\n");
        assertThat(diagnostics.snapshot().stderrSignal()).isEqualTo(Signal.RATE_LIMIT);
        emit(diagnostics, true, "ERROR: unexpected status 503 Service Unavailable: SELECT secret\n");
        assertThat(diagnostics.snapshot().stderrSignal()).isEqualTo(Signal.PROVIDER_UNAVAILABLE);
        emit(diagnostics, true, "Reconnecting... 1/5 - unexpected status 502 Bad Gateway: synthetic-token\n");
        assertThat(diagnostics.snapshot().stderrSignal()).isEqualTo(Signal.NETWORK_RECONNECT);
        assertThat(diagnostics.snapshot().safeSummary()).doesNotContain("Authorization", "Bearer", "synthetic", "SELECT", "secret");
    }

    @Test void oversizedMalformedAndUnknownLinesAreIgnoredWithoutDroppingCounters() {
        var diagnostics = new ProcessDiagnostics(Transport.CODEX_EXEC);
        emit(diagnostics, false, "x".repeat(9000) + "\nnot-json\n{\"type\":\"private-event\"}\n");
        emit(diagnostics, true, "x".repeat(9000) + "ERROR: unexpected status 429\n");
        assertThat(diagnostics.snapshot().phase()).isEqualTo(Phase.PROCESS_STARTED);
        assertThat(diagnostics.snapshot().execStdinWritten()).isFalse();
        assertThat(diagnostics.snapshot().stderrSignal()).isEqualTo(Signal.NONE);
        assertThat(diagnostics.snapshot().stdoutBytes()).isGreaterThan(9000);
        emit(diagnostics, false, "{\"type\":\"thread.started\"}\n");
        assertThat(diagnostics.snapshot().phase()).isEqualTo(Phase.THREAD_STARTED);
    }

    @Test void appStagesAreFixedAndCannotExposeUnknownStrings() {
        var diagnostics = new ProcessDiagnostics(Transport.CODEX_APP_SERVER);
        diagnostics.appPhase("답변 생성");
        diagnostics.appStdoutBytes(123);
        assertThat(diagnostics.snapshot().phase()).isEqualTo(Phase.WAIT_RESPONSE);
        assertThat(diagnostics.snapshot().stdoutBytes()).isEqualTo(123);
        assertThatThrownBy(() -> diagnostics.appPhase("synthetic-secret")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("synthetic-secret");
    }

    private static void emit(ProcessDiagnostics diagnostics, boolean stderr, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (stderr) diagnostics.stderr(bytes, bytes.length);
        else diagnostics.stdout(bytes, bytes.length);
    }
}
