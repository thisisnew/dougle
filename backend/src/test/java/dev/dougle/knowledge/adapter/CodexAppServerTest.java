package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.CuratorSettings;
import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class CodexAppServerTest {
    @TempDir Path directory;
    private Path home;
    private Path trace;
    private String executable;

    private CodexAppServer fixture(String mode, Duration timeout) throws Exception {
        home = Files.createDirectory(directory.resolve("profile"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(home.resolve("auth.json"), "{\"synthetic\":true}");
        Files.setPosixFilePermissions(home.resolve("auth.json"), PosixFilePermissions.fromString("rw-------"));
        trace = directory.resolve("trace.jsonl");
        Path wrapper = directory.resolve("fake-codex");
        Path script = Path.of("../scripts/fixtures/fake-codex-app-server.mjs").toRealPath();
        Files.writeString(wrapper, "#!/bin/sh\nexec node '" + script + "' '--fixture-mode=" + mode
                + "' '--fixture-trace=" + trace + "' \"$@\"\n");
        wrapper.toFile().setExecutable(true);
        executable = wrapper.toString();
        return new CodexAppServer(executable, home, timeout);
    }

    private CodexAppServer turnFixture(String events) throws Exception {
        var server = fixture("normal", Duration.ofSeconds(8));
        Path original = Path.of("../scripts/fixtures/fake-codex-app-server.mjs").toRealPath();
        String script = Files.readString(original);
        String marker = "    if (mode === 'request')";
        assertThat(script).contains(marker);
        Path synthetic = directory.resolve("synthetic-app-server.mjs");
        Files.writeString(synthetic, script.replace(marker, "    if (mode === 'normal') { " + events + "; return }\n" + marker));
        Path wrapper = Path.of(executable);
        Files.writeString(wrapper, Files.readString(wrapper).replace(original.toString(), synthetic.toString()));
        return server;
    }

    @Test
    void timeoutReportsCompletedMessageButIgnoredWrongTurnCompletion() throws Exception {
        var server = turnFixture("partial(); notification('item/completed', {item: {...item, text: 'private-final'}}); "
                + "notification('turn/completed', {turn: {id: 'private-other-turn', status: 'completed'}})");
        assertThatThrownBy(() -> server.generate("private-prompt", "", ignored -> {}))
                .isInstanceOfSatisfying(dev.dougle.knowledge.application.ProviderExecutionException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    var progress = error.diagnostic().appStream();
                    assertThat(progress.turnAccepted()).isTrue();
                    assertThat(progress.messages()).isEqualTo(1);
                    assertThat(progress.deltas()).isEqualTo(1);
                    assertThat(progress.completedMessages()).isEqualTo(1);
                    assertThat(progress.matchedCompletions()).isZero();
                    assertThat(progress.ignoredBindings()).isEqualTo(1);
                    assertThat(progress.protocolInvalid()).isZero();
                    assertThat(error.getMessage()).doesNotContain("private", home.toString());
                });
        assertFixtureStopped();
    }

    @Test
    void unterminatedCompletionLineIsCountedButNeverAccepted() throws Exception {
        var server = turnFixture("process.stdout.write(JSON.stringify({method: 'turn/completed', params: {threadId: 'fixture-thread', turn: {id: 'fixture-turn', status: 'completed'}}}))");
        assertThatThrownBy(() -> server.generate("synthetic", "", ignored -> {}))
                .isInstanceOfSatisfying(dev.dougle.knowledge.application.ProviderExecutionException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.diagnostic().appStream().turnAccepted()).isTrue();
                    assertThat(error.diagnostic().appStream().pendingLineBytes()).isPositive();
                    assertThat(error.diagnostic().appStream().matchedCompletions()).isZero();
                });
        assertFixtureStopped();
    }

    @Test
    void invalidCompletionRetainsTypedDiagnosticsWithoutReturningCandidate() throws Exception {
        var server = turnFixture("notification('turn/completed', {turn: {id: 'fixture-turn', status: 'completed'}})");
        assertThatThrownBy(() -> server.generate("private-prompt", "", ignored -> {}))
                .isInstanceOfSatisfying(dev.dougle.knowledge.application.ProviderExecutionException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.diagnostic().appStream().matchedCompletions()).isEqualTo(1);
                    assertThat(error.diagnostic().appStream().protocolInvalid()).isEqualTo(1);
                    assertThat(error.getMessage()).contains("자동 재전송하지 않습니다").doesNotContain("private");
                });
        assertFixtureStopped();
    }

    @Test
    void timeoutDistinguishesBlockedLocalCallbackWithoutRelaxingDeadline() throws Exception {
        var server = turnFixture("partial()");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> server.generate("synthetic", "", ignored -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            })).isInstanceOfSatisfying(dev.dougle.knowledge.application.ProviderExecutionException.class, error -> {
                assertThat(error.status()).isEqualTo(504);
                assertThat(entered.getCount()).isZero();
                assertThat(error.diagnostic().appStream().callbackActive()).isTrue();
                assertThat(error.diagnostic().appStream().messages()).isEqualTo(1);
            });
        } finally { release.countDown(); }
        assertFixtureStopped();
    }

    private void assertFixtureStopped() throws Exception {
        var first = JsonMapper.builder().build().readTree(Files.readAllLines(trace).getFirst());
        assertThat(ProcessHandle.of(first.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(Files.exists(Path.of(first.path("cwd").asString()))).isFalse();
        assertThat(Files.readAllLines(trace).stream().filter(line -> line.contains("turn/start")).count()).isEqualTo(1);
    }

    @Test
    void optInRoutesCodexAnswerThroughIsolatedStreamAndReleasesProcessAndLock() throws Exception {
        fixture("normal", Duration.ofSeconds(30));
        var model = new CliCuratorModel(executable, "unused", Duration.ofSeconds(30), new CuratorSettings("", ""), home.toString());
        var texts = new CopyOnWriteArrayList<String>();
        var partial = new CompletableFuture<String>();
        var releasePartial = new CountDownLatch(1);
        var result = new CompletableFuture<String>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { result.complete(model.generateWithText(Provider.CODEX, "synthetic-only", "fixture-model", text -> {
                texts.add(text);
                if (!text.isEmpty() && partial.complete(text)) {
                    try { releasePartial.await(); }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Partial output observation interrupted", error);
                    }
                }
            })); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        try {
            CompletableFuture.anyOf(partial, result).get(30, TimeUnit.SECONDS);
            assertThat(partial).isCompletedWithValue("가상 Codex 임시 출력");
            assertThat(result.isDone()).isFalse();
            releasePartial.countDown();
            assertThat(result.get(30, TimeUnit.SECONDS)).contains("R1", "claims").doesNotContain("임시");
            assertThat(texts).contains("가상 Codex 임시 출력");
            var first = JsonMapper.builder().build().readTree(Files.readAllLines(trace).getFirst());
            assertThat(Files.exists(Path.of(first.path("cwd").asString()))).isFalse();
            assertThat(ProcessHandle.of(first.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(Files.readString(home.resolve("auth.json"))).isEqualTo("{\"synthetic\":true}");
            assertThat(new CodexAppServer(executable, home, Duration.ofSeconds(30)).generate("synthetic", "", text -> {})).contains("claims");
        } finally { releasePartial.countDown(); worker.interrupt(); worker.join(5000); }
    }

    @Test
    void sendsRealImageInputWithPrivatePermissionsAndRemovesTemporaryFile() throws Exception {
        var server = fixture("image", Duration.ofSeconds(30));
        var image = new dev.dougle.knowledge.application.ModelImage("R1", "synthetic-image-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(server.generate("synthetic", "fixture-model", text -> {}, List.of(image))).contains("claims");
        String line = Files.readAllLines(trace).stream().filter(value -> value.contains("verifiedImage")).findFirst().orElseThrow();
        Path path = Path.of(JsonMapper.builder().build().readTree(line).path("verifiedImage").asString());
        assertThat(Files.exists(path)).isFalse();
        assertThat(Files.exists(path.getParent())).isFalse();
    }

    @Test
    void buffersNotificationsBeforeTurnResponse() throws Exception {
        var server = fixture("early", Duration.ofSeconds(30));
        var texts = new java.util.ArrayList<String>();
        assertThat(server.generate("synthetic", "", texts::add)).contains("claims");
        assertThat(texts).contains("가상 Codex 임시 출력");
    }

    @Test
    void rejectsExternalConfigBeforeSendingPrompt() throws Exception {
        var server = fixture("unsafe-config", Duration.ofSeconds(30));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
        assertThat(Files.readString(trace)).doesNotContain("thread/start", "turn/start");
    }

    @Test
    void rejectsUnsafeThreadBeforeSendingPrompt() throws Exception {
        var server = fixture("unsafe-thread", Duration.ofSeconds(30));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
        assertThat(Files.readString(trace)).contains("thread/start").doesNotContain("turn/start");
    }

    @Test
    void rejectsApprovalRequestWithoutRespondingOrRetrying() throws Exception {
        fixture("request", Duration.ofSeconds(30));
        var model = new CliCuratorModel(executable, "unused", Duration.ofSeconds(30), new CuratorSettings("", ""), home.toString());
        assertThatThrownBy(() -> model.generateWithText(Provider.CODEX, "synthetic", "", text -> {}))
                .isInstanceOf(LibraryException.class).hasMessageContaining("자동 재전송하지 않습니다");
        var lines = Files.readAllLines(trace);
        assertThat(lines.stream().filter(line -> line.contains("\"pid\"")).count()).isEqualTo(1);
        assertThat(lines.stream().filter(line -> line.contains("turn/start")).count()).isEqualTo(1);
        assertThat(String.join("\n", lines)).doesNotContain("\"id\":90");
    }

    @Test
    void cancelsProcessAndObservedDescendants() throws Exception {
        var server = fixture("hang", Duration.ofSeconds(60));
        var finished = new CompletableFuture<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { server.generate("synthetic", "", text -> {}, List.of(new dev.dougle.knowledge.application.ModelImage("R1", new byte[]{1, 2, 3}))); finished.complete(null); }
            catch (Throwable error) { finished.complete(error); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((!Files.exists(trace) || !Files.readString(trace).contains("childPid")) && !finished.isDone() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(finished.isDone()).as("Fixture must remain running until interruption").isFalse();
            assertThat(Files.readString(trace)).contains("childPid");
            Thread.sleep(200);
            worker.interrupt();
            assertThat(finished.get(5, TimeUnit.SECONDS)).isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(503));
            for (String line : Files.readAllLines(trace)) {
                var value = JsonMapper.builder().build().readTree(line);
                if (value.has("cwd")) assertThat(Files.exists(Path.of(value.path("cwd").asString()))).isFalse();
                for (String key : List.of("pid", "childPid")) if (value.has(key)) {
                    assertThat(ProcessHandle.of(value.path(key).asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                }
            }
        } finally { worker.interrupt(); worker.join(5000); }
    }

    @Test
    void enforcesTimeout() throws Exception {
        var server = fixture("hang", Duration.ofMillis(500));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {}))
                .isInstanceOfSatisfying(LibraryException.class, error -> {
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.getMessage()).contains("단계에서 제한 시간을 초과").doesNotContain("synthetic", home.toString());
                });
    }

    @Test
    void rejectsOversizedStdoutWithoutWaitingForTimeout() throws Exception {
        var server = fixture("large-line", Duration.ofSeconds(20));
        long start = System.nanoTime();
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void rejectsOversizedStderrWithoutExposingIt() throws Exception {
        var server = fixture("stderr-limit", Duration.ofSeconds(20));
        long start = System.nanoTime();
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class).hasMessageNotContaining("xxxx");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void rejectsUnexpectedTools() throws Exception {
        var server = fixture("tool", Duration.ofSeconds(30));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void rejectsWrongResponseId() throws Exception {
        var server = fixture("wrong-id", Duration.ofSeconds(30));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void processExitCannotBecomeSuccess() throws Exception {
        var server = fixture("exit", Duration.ofSeconds(30));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void refusesCustomConfigSymlinksAndSharedPermissionsBeforeLaunch() throws Exception {
        var server = fixture("normal", Duration.ofSeconds(30));
        Files.writeString(home.resolve("config.toml"), "synthetic = true");
        assertThatThrownBy(server::validateHome).isInstanceOf(java.io.IOException.class);
        Files.delete(home.resolve("config.toml"));
        Files.createSymbolicLink(home.resolve("version.json"), directory.resolve("outside"));
        assertThatThrownBy(server::validateHome).isInstanceOf(java.io.IOException.class);
        Files.delete(home.resolve("version.json"));
        Files.setPosixFilePermissions(home.resolve("auth.json"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(server::validateHome).isInstanceOf(java.io.IOException.class);
        assertThat(Files.exists(trace)).isFalse();
    }

    @Test
    void refusesNormalProfileAndRelativePath() {
        assertThatThrownBy(() -> new CodexAppServer("unused", Path.of("relative"), Duration.ofSeconds(1)).validateHome()).isInstanceOf(java.io.IOException.class);
        Path normal = Path.of(System.getProperty("user.home"), ".codex");
        assertThatThrownBy(() -> new CodexAppServer("unused", normal, Duration.ofSeconds(1)).validateHome()).isInstanceOf(java.io.IOException.class);
    }
}
