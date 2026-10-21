package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class CliCuratorModelTest {
    @TempDir Path directory;

    CliCuratorModel fixture(String script, Duration timeout) throws Exception {
        Path executable = directory.resolve("fake-cli");
        Files.writeString(executable, "#!/bin/sh\n" + script);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return new CliCuratorModel(executable.toString(), executable.toString(), timeout);
    }

    @Test
    void codexUsesIsolatedDirectoryStdinAndLastMessageFile() throws Exception {
        var model = fixture("cat > input.txt\n[ \"$(cat input.txt)\" = 'synthetic evidence' ] || exit 9\n"
                + "printf '%s' '{\"title\":\"fixture\",\"claims\":[]}' > answer.json\npwd\n", Duration.ofSeconds(5));
        assertThat(model.generate(Provider.CODEX, "synthetic evidence")).contains("fixture");
        assertThat(Files.exists(directory.resolve("input.txt"))).isFalse();
        var command = model.command(Provider.CODEX, directory);
        assertThat(command).contains("--ignore-user-config", "--ephemeral", "read-only", "shell_tool", "hooks", "apps", "plugins")
                .doesNotContain("--dangerously-bypass-approvals-and-sandbox", "resume");
    }

    @Test
    void claudeReadsStructuredOutputAndDisablesToolsAndMcp() throws Exception {
        var model = fixture("cat >/dev/null\nprintf '%s' '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"title\":\"fixture\",\"claims\":[]}}'", Duration.ofSeconds(5));
        assertThat(model.generate(Provider.CLAUDE, "synthetic")).contains("fixture");
        assertThat(model.command(Provider.CLAUDE, directory)).contains("--tools", "", "mcp__*", "--strict-mcp-config", "dontAsk")
                .doesNotContain("--dangerously-skip-permissions", "--continue");
    }

    @Test
    void nonzeroExitDoesNotLeakStderr() throws Exception {
        var model = fixture("echo 'secret-token' >&2\nexit 1", Duration.ofSeconds(5));
        assertThatThrownBy(() -> model.generate(Provider.CLAUDE, "synthetic"))
                .isInstanceOf(LibraryException.class).hasMessageNotContaining("secret-token");
    }

    @Test
    void authenticationFailureIsActionableWithoutExposingRawOutput() throws Exception {
        var model = fixture("cat >/dev/null\necho 'Not logged in secret-token' >&2\nexit 1", Duration.ofSeconds(5));
        assertThatThrownBy(() -> model.generate(Provider.CLAUDE, "synthetic"))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(503))
                .hasMessageContaining("인증").hasMessageNotContaining("secret-token");
    }

    @Test
    void timesOutAndKillsWaitingChild() throws Exception {
        var model = fixture("cat >/dev/null\nsleep 30", Duration.ofMillis(200));
        long start = System.nanoTime();
        assertThatThrownBy(() -> model.generate(Provider.CLAUDE, "synthetic"))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(504));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void rejectsOversizedOutputAndMissingCommand() throws Exception {
        var model = fixture("cat >/dev/null\nyes oversize", Duration.ofSeconds(5));
        assertThatThrownBy(() -> model.generate(Provider.CLAUDE, "synthetic")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> new CliCuratorModel(directory.resolve("missing").toString(), "missing")
                .generate(Provider.CODEX, "synthetic")).isInstanceOf(LibraryException.class);
    }

    @Test
    void rejectsClaudeErrorEnvelopeAndCodexMissingResult() throws Exception {
        var model = fixture("cat >/dev/null\nprintf '%s' '{\"type\":\"result\",\"subtype\":\"error\",\"is_error\":true}'", Duration.ofSeconds(5));
        assertThatThrownBy(() -> model.generate(Provider.CLAUDE, "synthetic")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> model.generate(Provider.CODEX, "synthetic")).isInstanceOf(LibraryException.class);
    }

    @Test
    void interruptKillsActualProcessAndChildAndRemovesTemporaryDirectory() throws Exception {
        Path parentFile = directory.resolve("parent.pid");
        Path childFile = directory.resolve("child.pid");
        Path workingDirectory = directory.resolve("cwd.txt");
        var model = fixture("cat >/dev/null\necho $$ > '" + parentFile + "'\npwd > '" + workingDirectory
                + "'\nsleep 30 &\necho $! > '" + childFile + "'\nwait\n", Duration.ofSeconds(30));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { model.generate(Provider.CLAUDE, "synthetic"); }
            catch (Throwable exception) { failure.set(exception); }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((!Files.exists(childFile) || Files.readString(childFile).isBlank()) && System.nanoTime() < deadline) Thread.sleep(10);
            long parent = Long.parseLong(Files.readString(parentFile).strip());
            long child = Long.parseLong(Files.readString(childFile).strip());
            worker.interrupt();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(LibraryException.class);
            assertThat(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(Files.exists(Path.of(Files.readString(workingDirectory).strip()))).isFalse();
        } finally { worker.interrupt(); worker.join(5000); }
    }
}
