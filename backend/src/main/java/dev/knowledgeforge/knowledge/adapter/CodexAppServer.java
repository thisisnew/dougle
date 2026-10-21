package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class CodexAppServer {
    static final List<String> DISABLED_FEATURES = List.of("shell_tool", "shell_snapshot", "apps", "plugins", "hooks", "multi_agent",
            "browser_use", "computer_use", "image_generation", "code_mode", "skill_search", "view_image", "skill_mcp_dependency_install",
            "remote_plugin", "tool_suggest", "memories", "remote_control");
    private final String executable;
    private final Path home;
    private final Duration timeout;

    CodexAppServer(String executable, Path home, Duration timeout) {
        this.executable = executable;
        this.home = home;
        this.timeout = timeout;
    }

    String generate(String prompt, String model, Consumer<String> text) {
        Path directory = null;
        try {
            Path profile = validateHome();
            try (var channel = FileChannel.open(profile.resolve(".forge-stream.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
                if (lock == null) throw new LibraryException(409, "Codex 전용 로그인 폴더가 사용 중입니다.");
                directory = Files.createTempDirectory("forge-app-server-").toRealPath();
                return run(profile, directory, prompt, model, text);
            }
        } catch (LibraryException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new LibraryException(502, "Codex 스트리밍 실행 실패: 전용 로그인 폴더·설정 격리·CLI 버전을 확인하세요. 자동 재전송하지 않습니다.");
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
        }
    }

    Path validateHome() throws IOException {
        if (!home.isAbsolute() || Files.isSymbolicLink(home) || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dedicated absolute profile required");
        }
        Path profile = home.toRealPath();
        for (String normal : List.of(Path.of(System.getProperty("user.home"), ".codex").toString(),
                System.getenv().getOrDefault("CODEX_HOME", Path.of(System.getProperty("user.home"), ".codex").toString()))) {
            Path usual = Path.of(normal).toAbsolutePath().normalize();
            if (Files.exists(usual)) usual = usual.toRealPath();
            if (profile.startsWith(usual) || usual.startsWith(profile)) throw new IOException("Shared profile forbidden");
        }
        if (!PosixFilePermissions.fromString("rwx------").containsAll(Files.getPosixFilePermissions(profile))) {
            throw new IOException("Private profile required");
        }
        Path auth = profile.resolve("auth.json");
        if (!Files.isRegularFile(auth, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(auth)
                || !PosixFilePermissions.fromString("rw-------").containsAll(Files.getPosixFilePermissions(auth))) {
            throw new IOException("Private file login required");
        }
        Set<String> files = Set.of("auth.json", ".forge-stream.lock", "version.json", "models_cache.json", ".last_migration", "installation_id");
        try (var entries = Files.list(profile)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Set.of("tmp", "skills").contains(name) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (name.equals("skills")) {
                        try (var skills = Files.list(entry)) {
                            for (Path skill : skills.toList()) {
                                if (!skill.getFileName().toString().equals(".system")
                                        || !Files.isDirectory(skill, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Custom skills forbidden");
                            }
                        }
                    }
                    continue;
                }
                if ((!files.contains(name) && !name.matches("(?:state|logs|goals|memories|queue)_[0-9]+\\.sqlite(?:-shm|-wal)?"))
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected profile content");
                }
            }
        }
        return profile;
    }

    List<String> command() {
        var arguments = new ArrayList<>(List.of(executable, "--no-daemon", "app-server", "--stdio", "--strict-config"));
        for (String config : List.of("approval_policy=\"never\"", "sandbox_mode=\"read-only\"", "web_search=\"disabled\"",
                "model_provider=\"openai\"", "cli_auth_credentials_store=\"file\"", "project_doc_max_bytes=0",
                "analytics.enabled=false", "feedback.enabled=false", "history.persistence=\"none\"")) {
            arguments.addAll(List.of("--config", config));
        }
        arguments.addAll(List.of("--enable", "skip_host_skill_discovery"));
        for (String feature : DISABLED_FEATURES) {
            arguments.addAll(List.of("--disable", feature));
        }
        return arguments;
    }

    private String run(Path profile, Path directory, String prompt, String model, Consumer<String> text) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command()).directory(directory.toFile());
        builder.environment().keySet().retainAll(Set.of("PATH", "USER", "LOGNAME", "LANG", "LC_ALL"));
        builder.environment().put("HOME", directory.toString());
        builder.environment().put("XDG_CONFIG_HOME", directory.toString());
        builder.environment().put("TMPDIR", directory.toString());
        builder.environment().put("CODEX_HOME", profile.toString());
        Process process = builder.start();
        Set<ProcessHandle> descendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var stdout = executor.submit(() -> exchange(process, directory, prompt, model, text));
            var stderr = executor.submit(() -> {
                byte[] buffer = new byte[4096];
                int total = 0;
                int count;
                while ((count = process.getErrorStream().read(buffer)) != -1) {
                    total += count;
                    if (total > 262144) throw new IOException("Stderr limit");
                }
                return true;
            });
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                process.descendants().forEach(descendants::add);
                if (stderr.isDone()) stderr.get();
                if (System.nanoTime() >= deadline) throw new LibraryException(504, "Codex 스트리밍 제한 시간을 초과했습니다.");
                try { return stdout.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException ignored) { }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LibraryException(503, "Codex 스트리밍이 중단되었습니다.");
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("App Server I/O failed");
        } finally {
            boolean interrupted = Thread.interrupted();
            process.descendants().forEach(descendants::add);
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) && System.nanoTime() < deadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            executor.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private String exchange(Process process, Path directory, String prompt, String model, Consumer<String> text) throws IOException {
        var session = new CodexAppServerSession(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
                directory, prompt, model, text);
        session.initialize();
        var input = new BufferedInputStream(process.getInputStream());
        var line = new ByteArrayOutputStream();
        int total = 0;
        int next;
        while ((next = input.read()) != -1) {
            if (++total > 1048576) throw new IOException("Stdout limit");
            if (next == '\n') {
                if (line.size() > 0) session.accept(line.toString(StandardCharsets.UTF_8));
                line.reset();
                if (session.result() != null) return session.result();
            } else {
                if (line.size() >= 262144) throw new IOException("Line limit");
                line.write(next);
            }
        }
        throw new IOException("App Server ended without a completed turn");
    }
}
