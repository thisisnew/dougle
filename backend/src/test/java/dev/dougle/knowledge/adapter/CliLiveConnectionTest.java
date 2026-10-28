package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.*;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class CliLiveConnectionTest {
    @TempDir Path directory;

    static class Store implements CliLaunchStore {
        Configuration value = new Configuration("", "", 0);
        public Configuration current() { return value; }
        public boolean replace(String codex, String claude, int revision) {
            if (revision != value.revision()) return false;
            value = new Configuration(codex, claude, revision + 1);
            return true;
        }
    }

    Path executable(String body) throws Exception {
        Path path = directory.resolve("provider-" + java.util.UUID.randomUUID());
        Files.writeString(path, "#!/bin/sh\n" + body);
        assertThat(path.toFile().setExecutable(true)).isTrue();
        return path;
    }

    CliLaunchController controller(CliLaunchConfiguration settings, String command) {
        CliDiscovery discovery = provider -> List.of(new CliDiscovery.Candidate("company", command));
        return new CliLaunchController(settings, discovery, new ProviderConnectionAdapter(settings, Duration.ofSeconds(3)));
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void authenticatesActivatesExistingRunnerAndSurvivesRestart(Provider provider) throws Exception {
        Path path = executable("case \"$*\" in\n'auth --help') echo '  status  Check';;\n"
                + "'auth status') echo '{\"loggedIn\":true}';;\n'login status') echo 'Logged in using ChatGPT';;\n"
                + "*) cat >/dev/null; echo '{\"title\":\"new-profile\",\"claims\":[]}' > answer.json; "
                + "echo '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"structured_output\":{\"title\":\"new-profile\",\"claims\":[]}}';;\nesac\n");
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, "missing-codex", "missing-claude");
        var runner = new CliCuratorModel(launch, new CuratorSettings("", ""), "");
        var other = provider == Provider.CLAUDE ? Provider.CODEX : Provider.CLAUDE;
        String previous = launch.binding(provider), otherBinding = launch.binding(other);
        var result = controller(launch, path.toString()).connect(provider, new CliLaunchController.Selection(path.toString(), 0));
        assertThat(result.state()).as(result.toString()).isEqualTo("AUTHENTICATED");
        assertThat(result.remoteConnection()).isEqualTo("NOT_CHECKED");
        assertThat(launch.command(provider)).isEqualTo(path.toString());
        assertThat(launch.binding(provider)).isNotEqualTo(previous);
        assertThat(launch.binding(other)).isEqualTo(otherBinding);
        assertThat(launch.view().restartRequired()).isFalse();
        assertThat(runner.generate(provider, "synthetic prompt")).contains("new-profile");
        var restarted = new CliLaunchConfiguration(store, "missing-codex", "missing-claude");
        assertThat(restarted.command(provider)).isEqualTo(path.toString());
        assertThat(new ProviderConnectionAdapter(restarted, Duration.ofSeconds(2)).check(provider).state()).isEqualTo("AUTHENTICATED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"echo malformed", "exit 9", "sleep 10", "echo '  status  Check'; exit 7"})
    void failedProbeDoesNotChangePersistenceOrActiveBinding(String body) throws Exception {
        Path path = executable(body);
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, "original-codex", "original-claude");
        var result = controller(launch, path.toString()).connect(Provider.CLAUDE, new CliLaunchController.Selection(path.toString(), 0));
        assertThat(result.state()).isIn("CHECK_FAILED", "UNSUPPORTED");
        assertThat(store.current().revision()).isZero();
        assertThat(launch.command(Provider.CLAUDE)).isEqualTo("original-claude");
    }

    @Test
    void loginRequiredUsesSelectedProfileForLoginAndRecheck() throws Exception {
        Path marker = directory.resolve("signed-in");
        Path path = executable("case \"$*\" in\n'auth --help') echo '  status  Check';;\n"
                + "'auth status') if [ -f '" + marker + "' ]; then echo '{\"loggedIn\":true}'; else echo '{\"loggedIn\":false}'; exit 1; fi;;\n"
                + "'auth login') touch '" + marker + "';;\n*) exit 99;;\nesac\n");
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, "missing", "missing");
        assertThat(controller(launch, path.toString()).connect(Provider.CLAUDE, new CliLaunchController.Selection(path.toString(), 0)).state()).isEqualTo("LOGIN_REQUIRED");
        var login = new ProviderLoginAdapter(launch, new ProviderConnectionAdapter(launch, Duration.ofSeconds(2)), Duration.ofSeconds(3));
        try {
            var started = login.start(Provider.CLAUDE, true);
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (login.status(started.id()).state().equals("PENDING") && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(login.status(started.id()).state()).isEqualTo("AUTHENTICATED");
            assertThat(marker).exists();
        } finally { login.close(); }
    }

    @Test
    void staleRevisionAndUndiscoveredCommandsNeverExecute() throws Exception {
        Path marker = directory.resolve("must-not-run");
        Path path = executable("touch '" + marker + "'\n");
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, "missing", "missing");
        var controller = controller(launch, path.toString());
        assertThatThrownBy(() -> controller.connect(Provider.CLAUDE, new CliLaunchController.Selection(path.toString(), 1))).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> controller.connect(Provider.CLAUDE, new CliLaunchController.Selection("/not-discovered", 0))).isInstanceOf(LibraryException.class);
        assertThat(marker).doesNotExist();
        assertThat(store.current().revision()).isZero();
    }

    @Test
    void runningAnswerCannotSwitchExecutionEnvironment() throws Exception {
        Path marker = directory.resolve("answer-started");
        Path release = directory.resolve("answer-release");
        Path path = executable("cat >/dev/null\ntouch '" + marker + "'\nwhile [ ! -f '" + release + "' ]; do sleep 0.05; done\n"
                + "echo '{\"title\":\"original-profile\",\"claims\":[]}' > answer.json\n");
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, path.toString(), "unused");
        var runner = new CliCuratorModel(launch, new CuratorSettings("", ""), "");
        var answer = new java.util.concurrent.CompletableFuture<String>();
        var worker = Thread.ofVirtual().start(() -> {
            try { answer.complete(runner.generate(Provider.CODEX, "synthetic")); }
            catch (Throwable exception) { answer.completeExceptionally(exception); }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(marker).exists();
            assertThatThrownBy(() -> launch.activate(Provider.CODEX, "different", 0)).isInstanceOf(LibraryException.class);
            assertThat(store.current().revision()).isZero();
            Files.writeString(release, "done");
            assertThat(answer.get(5, TimeUnit.SECONDS)).contains("original-profile");
        } finally { Files.writeString(release, "done"); worker.interrupt(); worker.join(5000); }
    }

    @Test
    void executionLeaseBlocksChangesUntilWorkFinishes() throws Exception {
        var store = new Store();
        var launch = new CliLaunchConfiguration(store, "original", "original");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Thread.ofVirtual().start(() -> {
            try (var lease = CliExecutionGate.read()) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> launch.activate(Provider.CLAUDE, "new", 0)).isInstanceOf(LibraryException.class);
            assertThatThrownBy(() -> launch.save("new", "new", 0, true)).isInstanceOf(LibraryException.class);
            assertThat(store.current().revision()).isZero();
        } finally { release.countDown(); worker.join(3000); }
        launch.activate(Provider.CLAUDE, "new", 0);
        assertThat(launch.command(Provider.CLAUDE)).isEqualTo("new");
    }
}
