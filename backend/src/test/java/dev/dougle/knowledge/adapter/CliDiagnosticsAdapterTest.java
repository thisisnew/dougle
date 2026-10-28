package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.CuratorSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class CliDiagnosticsAdapterTest {
    @TempDir Path directory;

    CliDiagnosticsAdapter fixture(String script) throws Exception {
        Path executable = directory.resolve("fake-cli");
        Files.writeString(executable, "#!/bin/sh\n[ \"$#\" = 1 ] && [ \"$1\" = '--version' ] || exit 9\n"
                + "[ -z \"$(cat)\" ] || exit 8\n" + script);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return new CliDiagnosticsAdapter(executable.toString(), executable.toString(), new CuratorSettings("synthetic-model", ""));
    }

    @Test
    void reportsOnlyParsedVersionAndNeverClaimsAuthenticationOrRemoteConnectivity() throws Exception {
        var codex = fixture("echo 'codex-cli 0.1.0'").probe(Provider.CODEX);
        assertThat(codex.status()).isEqualTo("AVAILABLE");
        assertThat(codex.version()).isEqualTo("0.1.0");
        assertThat(codex.requestedModel()).isEqualTo("synthetic-model");
        assertThat(codex.authentication()).isEqualTo("UNKNOWN");
        assertThat(codex.remoteConnection()).isEqualTo("NOT_CHECKED");
        assertThat(fixture("echo '1.2.3 (Claude Code)'").probe(Provider.CLAUDE).version()).isEqualTo("1.2.3");
    }

    @Test
    void unknownOrFailedOutputNeverLeaksSecrets() throws Exception {
        var unknown = fixture("echo 'secret-token codex-cli 0.1.0'").probe(Provider.CODEX);
        assertThat(unknown.status()).isEqualTo("UNRECOGNIZED");
        assertThat(unknown.version()).isNull();
        assertThat(unknown.toString()).doesNotContain("secret-token");
        var failed = fixture("echo 'secret-token' >&2; exit 1").probe(Provider.CODEX);
        assertThat(failed.status()).isEqualTo("UNAVAILABLE");
        assertThat(failed.toString()).doesNotContain("secret-token");
        assertThat(new CliDiagnosticsAdapter(directory.resolve("missing").toString(), "unused", new CuratorSettings("", ""))
                .probe(Provider.CODEX).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void probeHasBoundedTimeoutAndReleasesSlot() throws Exception {
        var adapter = fixture("sleep 30");
        long started = System.nanoTime();
        assertThat(adapter.probe(Provider.CODEX).status()).isEqualTo("UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(9));
        fixture("echo 'codex-cli 0.1.0'");
        assertThat(adapter.probe(Provider.CODEX).status()).isEqualTo("AVAILABLE");
    }
}
