package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.ProviderConnection;
import dev.dougle.knowledge.application.ProviderLogin;
import dev.dougle.library.application.LibraryException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class ProviderLoginAdapter implements ProviderLogin {
    private final CliLaunchConfiguration launch;
    private final ProviderConnection connection;
    private final Duration timeout;
    private final String codexStreamHome;
    private Status current;
    private Process process;
    private boolean closed;

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderLoginAdapter(CliLaunchConfiguration launch, ProviderConnection connection,
            @org.springframework.beans.factory.annotation.Value("${forge.ai.codex-stream-home:}") String codexStreamHome) {
        this(launch, connection, Duration.ofMinutes(3), codexStreamHome);
    }
    ProviderLoginAdapter(CliLaunchConfiguration launch, ProviderConnection connection, Duration timeout) {
        this(launch, connection, timeout, "");
    }
    ProviderLoginAdapter(CliLaunchConfiguration launch, ProviderConnection connection, Duration timeout, String codexStreamHome) {
        this.launch = launch; this.connection = connection; this.timeout = timeout;
        this.codexStreamHome = codexStreamHome;
    }

    @Override
    public synchronized Status start(Provider provider, boolean acknowledged) {
        try (var lease = dev.dougle.knowledge.application.CliExecutionGate.read()) {
            return startLocked(provider, acknowledged);
        }
    }

    private Status startLocked(Provider provider, boolean acknowledged) {
        if (!acknowledged) throw new LibraryException(400, "브라우저 로그인과 제공자 자격 증명 저장에 동의하세요. 자료 전송 승인은 별도입니다.");
        if (closed || (current != null && current.state().equals("PENDING"))) throw new LibraryException(409, "로그인이 진행 중이거나 앱이 종료 중입니다.");
        var checked = connection.check(provider);
        if (!Set.of("AUTHENTICATED", "LOGIN_REQUIRED").contains(checked.state())) {
            throw new LibraryException(409, "이 제공자의 로그인 실행 환경을 확인하지 못했습니다. 먼저 연결 상태를 확인하세요.");
        }
        current = new Status(UUID.randomUUID().toString(), provider.name(), checked.state().equals("AUTHENTICATED") ? "AUTHENTICATED" : "PENDING");
        Status started = current;
        String executable = launch.command(provider);
        if (started.state().equals("PENDING")) Thread.ofVirtual().name("forge-provider-login").start(() -> execute(started, provider, executable));
        return started;
    }

    @Override
    public synchronized Status status(String id) {
        if (current == null || !current.id().equals(id)) throw new LibraryException(404, "로그인 요청을 찾을 수 없습니다. 앱 재시작 후 다시 연결하세요.");
        return current;
    }

    @Override
    public synchronized Status cancel(String id) {
        var status = status(id);
        if (status.state().equals("PENDING")) {
            current = new Status(status.id(), status.provider(), "CANCELLED");
            stop(process);
        }
        return current;
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (current != null) cancel(current.id());
    }

    private synchronized void finish(Status started, String state) {
        if (current != null && current.id().equals(started.id()) && current.state().equals("PENDING")) {
            current = new Status(started.id(), started.provider(), state);
        }
    }

    private void execute(Status started, Provider provider, String executable) {
        try (var lease = dev.dougle.knowledge.application.CliExecutionGate.read()) {
            if (!java.util.Objects.equals(executable, launch.command(provider))) { finish(started, "FAILED"); return; }
            executeLocked(started, provider);
        } catch (RuntimeException exception) { finish(started, "FAILED"); }
    }

    private void executeLocked(Status started, Provider provider) {
        Path directory = null;
        Process owned = null;
        var executor = Executors.newFixedThreadPool(2);
        var descendants = java.util.concurrent.ConcurrentHashMap.<ProcessHandle>newKeySet();
        try {
            directory = Files.createTempDirectory("forge-login-");
            var command = provider == Provider.CODEX
                    ? (codexStreamHome.isBlank() ? List.of(launch.command(provider), "login")
                    : List.of(launch.command(provider), "--config", "cli_auth_credentials_store=\"file\"", "login"))
                    : List.of(launch.command(provider), "auth", "login");
            var builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().keySet().retainAll(Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL",
                    "SYSTEMROOT", "SystemRoot", "WINDIR", "CODEX_HOME"));
            CliProcessPath.includeCommandDirectory(builder);
            if (provider == Provider.CODEX && !codexStreamHome.isBlank()) {
                CodexProfile.configure(builder, CodexProfile.prepare(codexStreamHome), directory);
            }
            synchronized (this) {
                if (closed || !status(started.id()).state().equals("PENDING")) return;
                ShellAliasLaunch.apply(builder);
                owned = builder.start();
                process = owned;
            }
            owned.getOutputStream().close();
            Process running = owned;
            var stdout = executor.submit(() -> discard(running.getInputStream()));
            var stderr = executor.submit(() -> discard(running.getErrorStream()));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (running.isAlive()) {
                running.descendants().forEach(descendants::add);
                if (stdout.isDone()) stdout.get();
                if (stderr.isDone()) stderr.get();
                if (System.nanoTime() >= deadline) { finish(started, "TIMED_OUT"); return; }
                running.waitFor(50, TimeUnit.MILLISECONDS);
            }
            stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (status(started.id()).state().equals("PENDING")) {
                finish(started, running.exitValue() == 0 && connection.check(provider).state().equals("AUTHENTICATED") ? "AUTHENTICATED" : "FAILED");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(started, "FAILED");
        } catch (Exception exception) {
            finish(started, "FAILED");
        } finally {
            descendants.forEach(ProcessHandle::destroyForcibly);
            stop(owned);
            if (owned != null) {
                try { owned.getInputStream().close(); } catch (IOException ignored) { }
                try { owned.getErrorStream().close(); } catch (IOException ignored) { }
            }
            executor.shutdownNow();
            synchronized (this) { if (process == owned) process = null; }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
        }
    }

    private boolean discard(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(65537);
        boolean exceeded = bytes.length > 65536;
        java.util.Arrays.fill(bytes, (byte) 0);
        if (exceeded) throw new IOException("Login output limit");
        return true;
    }

    private static void stop(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
