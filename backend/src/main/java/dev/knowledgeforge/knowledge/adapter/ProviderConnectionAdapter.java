package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CliLaunchConfiguration;
import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.ProviderConnection;
import dev.knowledgeforge.library.application.LibraryException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProviderConnectionAdapter implements ProviderConnection {
    private final CliLaunchConfiguration launch;
    private final Duration timeout;
    private final String codexStreamHome;
    private final Semaphore slot = new Semaphore(1);
    record Result(int exit, String stdout, String stderr) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderConnectionAdapter(CliLaunchConfiguration launch,
            @org.springframework.beans.factory.annotation.Value("${forge.ai.codex-stream-home:}") String codexStreamHome) {
        this(launch, Duration.ofSeconds(5), codexStreamHome);
    }
    ProviderConnectionAdapter(CliLaunchConfiguration launch, Duration timeout) { this(launch, timeout, ""); }
    ProviderConnectionAdapter(CliLaunchConfiguration launch, Duration timeout, String codexStreamHome) {
        this.launch = launch; this.timeout = timeout; this.codexStreamHome = codexStreamHome;
    }

    @Override
    public Status check(Provider provider) {
        if (provider == Provider.CODEX && !codexStreamHome.isBlank()) return status(provider, "UNSUPPORTED");
        if (!slot.tryAcquire()) throw new LibraryException(409, "연결 상태를 확인 중입니다. 잠시 후 다시 확인하세요.");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("forge-connection-");
            String executable = launch.command(provider);
            if (provider == Provider.CLAUDE) {
                var help = run(List.of(executable, "auth", "--help"), directory);
                if (help.exit() != 0 || !java.util.regex.Pattern.compile("(?m)^\\s+status(?:\\s|$)").matcher(help.stdout()).find()) {
                    return status(provider, "UNSUPPORTED");
                }
                return status(provider, classify(provider, run(List.of(executable, "auth", "status"), directory)));
            }
            return status(provider, classify(provider, run(List.of(executable, "login", "status"), directory)));
        } catch (java.io.FileNotFoundException exception) {
            return status(provider, "TOOL_UNAVAILABLE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return status(provider, "CHECK_FAILED");
        } catch (Exception exception) {
            return status(provider, "CHECK_FAILED");
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
            slot.release();
        }
    }

    static String classify(Provider provider, Result result) {
        if (provider == Provider.CODEX) {
            String output = (result.stdout() + "\n" + result.stderr()).strip();
            if (result.exit() == 0 && output.equals("Logged in using ChatGPT")) return "AUTHENTICATED";
            if (result.exit() == 1 && output.equalsIgnoreCase("Not logged in")) return "LOGIN_REQUIRED";
            return "CHECK_FAILED";
        }
        try {
            var value = JsonMapper.builder().build().readTree(result.stdout());
            if (!value.isObject() || !value.path("loggedIn").isBoolean()) return "CHECK_FAILED";
            if (result.exit() == 0 && value.path("loggedIn").asBoolean()) return "AUTHENTICATED";
            if (result.exit() == 1 && !value.path("loggedIn").asBoolean()) return "LOGIN_REQUIRED";
        } catch (RuntimeException ignored) { }
        return "CHECK_FAILED";
    }

    private Status status(Provider provider, String state) {
        return new Status(provider.name(), state, Instant.now().toString(), "NOT_CHECKED");
    }

    private Result run(List<String> command, Path directory) throws Exception {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().retainAll(Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL",
                "SYSTEMROOT", "SystemRoot", "WINDIR", "CODEX_HOME"));
        CliProcessPath.includeCommandDirectory(builder);
        Process process;
        try { process = builder.start(); }
        catch (IOException exception) { throw new java.io.FileNotFoundException("Provider executable unavailable"); }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var descendants = java.util.concurrent.ConcurrentHashMap.<ProcessHandle>newKeySet();
        try {
            process.getOutputStream().close();
            var stdout = executor.submit(() -> bounded(process.getInputStream()));
            var stderr = executor.submit(() -> bounded(process.getErrorStream()));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive()) {
                process.descendants().forEach(descendants::add);
                if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
                process.waitFor(20, TimeUnit.MILLISECONDS);
            }
            return new Result(process.exitValue(), stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                    stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } finally {
            process.descendants().forEach(descendants::add);
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try { process.getInputStream().close(); }
            finally {
                try { process.getErrorStream().close(); }
                finally { executor.shutdownNow(); }
            }
        }
    }

    private String bounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(8193);
        if (bytes.length > 8192) throw new IOException("Provider status limit");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
