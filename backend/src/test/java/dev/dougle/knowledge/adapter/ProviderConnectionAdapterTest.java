package dev.dougle.knowledge.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class ProviderConnectionAdapterTest {
    @TempDir Path directory;

    ProviderConnectionAdapter adapter(String body) throws Exception {
        return adapter(body, Duration.ofSeconds(3));
    }

    ProviderConnectionAdapter adapter(String body, Duration timeout) throws Exception {
        Path executable = directory.resolve("fake-provider");
        Files.writeString(executable, "#!/bin/sh\n[ -z \"$(cat)\" ] || exit 91\n" + body);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var launch = mock(CliLaunchConfiguration.class);
        when(launch.command(any())).thenReturn(executable.toString());
        return new ProviderConnectionAdapter(launch, timeout);
    }

    @Test
    void codexChecksOnlyLoginStatusWithoutPromptOrVersionInference() throws Exception {
        var result = adapter("[ \"$*\" = 'login status' ] || exit 92\necho 'Logged in using ChatGPT' >&2\n").check(Provider.CODEX);
        assertThat(result.state()).isEqualTo("AUTHENTICATED");
        assertThat(result.remoteConnection()).isEqualTo("NOT_CHECKED");
        assertThat(adapter("echo 'Not logged in' >&2; exit 1").check(Provider.CODEX).state()).isEqualTo("LOGIN_REQUIRED");
        assertThat(adapter("echo 'codex-cli 1.0.0'").check(Provider.CODEX).state()).isEqualTo("CHECK_FAILED");
        assertThat(adapter("echo secret").check(Provider.CODEX).reason()).isEqualTo("AUTH_RESPONSE_UNRECOGNIZED");
        assertThat(adapter("echo secret; exit 9").check(Provider.CODEX).reason()).isEqualTo("AUTH_STATUS_REJECTED");
    }

    @Test
    void claudeRequiresAuthCapabilityBeforeStatusCommand() throws Exception {
        var result = adapter("if [ \"$*\" = 'auth --help' ]; then echo '  status  Show authentication status'; exit 0; fi\n"
                + "[ \"$*\" = 'auth status' ] || exit 93\necho '{\"loggedIn\":true,\"email\":\"private@example.invalid\",\"token\":\"secret\"}'")
                .check(Provider.CLAUDE);
        assertThat(result.state()).isEqualTo("AUTHENTICATED");
        assertThat(result.toString()).doesNotContain("private@", "secret");
        var old = adapter("[ \"$*\" = 'auth --help' ] || touch '" + directory.resolve("must-not-run") + "'\necho 'Old CLI help'");
        assertThat(old.check(Provider.CLAUDE).state()).isEqualTo("UNSUPPORTED");
        assertThat(directory.resolve("must-not-run")).doesNotExist();
    }

    @Test
    void rejectsMalformedContradictoryAndSecretResponses() {
        assertThat(ProviderConnectionAdapter.classify(Provider.CLAUDE, new ProviderConnectionAdapter.Result(1, "{\"loggedIn\":true}", ""))).isEqualTo("CHECK_FAILED");
        assertThat(ProviderConnectionAdapter.classify(Provider.CLAUDE, new ProviderConnectionAdapter.Result(0, "{\"loggedIn\":\"true\"}", ""))).isEqualTo("CHECK_FAILED");
        assertThat(ProviderConnectionAdapter.classify(Provider.CLAUDE, new ProviderConnectionAdapter.Result(1, "{\"loggedIn\":false}", ""))).isEqualTo("LOGIN_REQUIRED");
        assertThat(ProviderConnectionAdapter.classify(Provider.CLAUDE, new ProviderConnectionAdapter.Result(0, "secret", ""))).isEqualTo("CHECK_FAILED");
        assertThat(ProviderConnectionAdapter.classify(Provider.CODEX, new ProviderConnectionAdapter.Result(0, "Logged in using ChatGPT\nsecret", ""))).isEqualTo("CHECK_FAILED");
    }

    @Test
    void defaultDeadlineAllowsColdProviderStartup() throws Exception {
        Path executable = directory.resolve("cold-provider");
        Files.writeString(executable, "#!/bin/sh\nsleep 6\necho 'Logged in using ChatGPT' >&2\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var launch = mock(CliLaunchConfiguration.class);
        when(launch.command(Provider.CODEX)).thenReturn(executable.toString());
        assertThat(new ProviderConnectionAdapter(launch, "").check(Provider.CODEX).state()).isEqualTo("AUTHENTICATED");
    }

    @Test
    void boundedTimeoutOutputAndMissingToolNeverLeakRawErrors() throws Exception {
        var adapter = adapter("sleep 30", Duration.ofMillis(200));
        long started = System.nanoTime();
        assertThat(adapter.check(Provider.CODEX).state()).isEqualTo("CHECK_FAILED");
        assertThat(adapter.check(Provider.CODEX).reason()).isEqualTo("TIMED_OUT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(adapter("head -c 9000 /dev/zero").check(Provider.CODEX).state()).isEqualTo("CHECK_FAILED");
        Files.delete(directory.resolve("fake-provider"));
        assertThat(adapter.check(Provider.CODEX).state()).isEqualTo("TOOL_UNAVAILABLE");
    }
}
