package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.CuratorSettings;
import dev.knowledgeforge.library.application.LibraryException;
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

    @Test
    void optInRoutesCodexAnswerThroughIsolatedStreamAndReleasesProcessAndLock() throws Exception {
        fixture("normal", Duration.ofSeconds(8));
        var model = new CliCuratorModel(executable, "unused", Duration.ofSeconds(8), new CuratorSettings("", ""), home.toString());
        var texts = new CopyOnWriteArrayList<String>();
        var partial = new CountDownLatch(1);
        var result = new CompletableFuture<String>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { result.complete(model.generateWithText(Provider.CODEX, "synthetic-only", "fixture-model", text -> {
                texts.add(text); if (!text.isEmpty()) partial.countDown();
            })); } catch (Throwable error) { result.completeExceptionally(error); }
        });
        try {
            assertThat(partial.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.isDone()).isFalse();
            assertThat(result.get(8, TimeUnit.SECONDS)).contains("R1", "claims").doesNotContain("임시");
            assertThat(texts).contains("가상 Codex 임시 출력");
            var first = JsonMapper.builder().build().readTree(Files.readAllLines(trace).getFirst());
            assertThat(Files.exists(Path.of(first.path("cwd").asString()))).isFalse();
            assertThat(ProcessHandle.of(first.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(Files.readString(home.resolve("auth.json"))).isEqualTo("{\"synthetic\":true}");
            assertThat(new CodexAppServer(executable, home, Duration.ofSeconds(8)).generate("synthetic", "", text -> {})).contains("claims");
        } finally { worker.interrupt(); worker.join(5000); }
    }

    @Test
    void sendsRealImageInputWithPrivatePermissionsAndRemovesTemporaryFile() throws Exception {
        var server = fixture("image", Duration.ofSeconds(8));
        var image = new dev.knowledgeforge.knowledge.application.ModelImage("R1", "synthetic-image-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(server.generate("synthetic", "fixture-model", text -> {}, List.of(image))).contains("claims");
        String line = Files.readAllLines(trace).stream().filter(value -> value.contains("verifiedImage")).findFirst().orElseThrow();
        Path path = Path.of(JsonMapper.builder().build().readTree(line).path("verifiedImage").asString());
        assertThat(Files.exists(path)).isFalse();
        assertThat(Files.exists(path.getParent())).isFalse();
    }

    @Test
    void buffersNotificationsBeforeTurnResponse() throws Exception {
        var server = fixture("early", Duration.ofSeconds(8));
        var texts = new java.util.ArrayList<String>();
        assertThat(server.generate("synthetic", "", texts::add)).contains("claims");
        assertThat(texts).contains("가상 Codex 임시 출력");
    }

    @Test
    void rejectsExternalConfigBeforeSendingPrompt() throws Exception {
        var server = fixture("unsafe-config", Duration.ofSeconds(8));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
        assertThat(Files.readString(trace)).doesNotContain("thread/start", "turn/start");
    }

    @Test
    void rejectsUnsafeThreadBeforeSendingPrompt() throws Exception {
        var server = fixture("unsafe-thread", Duration.ofSeconds(8));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
        assertThat(Files.readString(trace)).contains("thread/start").doesNotContain("turn/start");
    }

    @Test
    void rejectsApprovalRequestWithoutRespondingOrRetrying() throws Exception {
        fixture("request", Duration.ofSeconds(8));
        var model = new CliCuratorModel(executable, "unused", Duration.ofSeconds(8), new CuratorSettings("", ""), home.toString());
        assertThatThrownBy(() -> model.generateWithText(Provider.CODEX, "synthetic", "", text -> {}))
                .isInstanceOf(LibraryException.class).hasMessageContaining("자동 재전송하지 않습니다");
        var lines = Files.readAllLines(trace);
        assertThat(lines.stream().filter(line -> line.contains("\"pid\"")).count()).isEqualTo(1);
        assertThat(lines.stream().filter(line -> line.contains("turn/start")).count()).isEqualTo(1);
        assertThat(String.join("\n", lines)).doesNotContain("\"id\":90");
    }

    @Test
    void cancelsProcessAndObservedDescendants() throws Exception {
        var server = fixture("hang", Duration.ofSeconds(30));
        var finished = new CompletableFuture<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try { server.generate("synthetic", "", text -> {}, List.of(new dev.knowledgeforge.knowledge.application.ModelImage("R1", new byte[]{1, 2, 3}))); finished.complete(null); }
            catch (Throwable error) { finished.complete(error); }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(trace) || !Files.readString(trace).contains("childPid")) && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.readString(trace)).contains("childPid");
            Thread.sleep(200);
            worker.interrupt();
            assertThat(finished.get(5, TimeUnit.SECONDS)).isInstanceOf(LibraryException.class);
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
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(504));
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
        var server = fixture("tool", Duration.ofSeconds(8));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void rejectsWrongResponseId() throws Exception {
        var server = fixture("wrong-id", Duration.ofSeconds(8));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void processExitCannotBecomeSuccess() throws Exception {
        var server = fixture("exit", Duration.ofSeconds(8));
        assertThatThrownBy(() -> server.generate("synthetic", "", text -> {})).isInstanceOf(LibraryException.class);
    }

    @Test
    void refusesCustomConfigSymlinksAndSharedPermissionsBeforeLaunch() throws Exception {
        var server = fixture("normal", Duration.ofSeconds(8));
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
