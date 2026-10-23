package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitRunner {
    private final Path bundledHome;

    public GitRunner() {
        this("");
    }

    @Autowired
    public GitRunner(@Value("${forge.git-home:}") String bundledHome) {
        this.bundledHome = bundledHome.isBlank() ? null : Path.of(bundledHome);
        if (this.bundledHome != null && !this.bundledHome.isAbsolute()) {
            throw new IllegalArgumentException("Bundled Git home must be absolute");
        }
    }

    ProcessBuilder command(Path directory, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        if (bundledHome == null) {
            command.add("git");
        } else {
            Path root = bundledHome.toRealPath();
            for (String relative : List.of("bin/git", "libexec/git-core/git", "libexec/git-core/git-remote-https")) {
                Path binary = root.resolve(relative).toRealPath();
                if (!binary.startsWith(root) || !Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
                    throw new IOException("내장 Git 실행 파일을 확인할 수 없습니다. 앱을 다시 설치해 주세요.");
                }
            }
            command.add(root.resolve("bin/git").toString());
            command.add("--exec-path=" + root.resolve("libexec/git-core"));
        }
        command.addAll(List.of("-c", "core.hooksPath=" + directory.resolve("disabled-hooks"),
                "-c", "credential.helper=", "-c", "protocol.file.allow=never", "-c", "protocol.ext.allow=never",
                "-c", "http.followRedirects=false", "-c", "http.sslVerify=true", "-c", "core.fsmonitor=false",
                "-c", "init.templateDir="));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        restrictEnvironment(builder.environment());
        if (bundledHome != null) {
            String inheritedPath = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", Path.of(command.getFirst()).getParent()
                    + (inheritedPath.isBlank() ? "" : java.io.File.pathSeparator + inheritedPath));
        }
        return builder;
    }

    static void restrictEnvironment(java.util.Map<String, String> environment) {
        environment.keySet().removeIf(key -> key.startsWith("GIT_"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    public byte[] run(Path directory, int limit, String... arguments) throws Exception {
        Process process = command(directory, arguments).start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> process.getInputStream().readNBytes(limit + 1));
            var errors = executor.submit(() -> process.getErrorStream().readNBytes(64 * 1024));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    if (System.nanoTime() > deadline || diskUsage(directory) > 100L * 1024 * 1024) {
                        throw new LibraryException(413, "Git 수집 시간(60초) 또는 임시 저장소(100 MiB) 제한을 초과했습니다.");
                    }
                }
                byte[] bytes = output.get();
                errors.get();
                if (process.exitValue() != 0) throw new IOException("Git command failed; check public repository access");
                if (bytes.length > limit) throw new LibraryException(413, "Git 수집 결과가 제한을 초과했습니다.");
                return bytes;
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private long diskUsage(Path directory) throws IOException {
        long[] size = {0};
        int[] count = {0};
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                size[0] += attributes.size();
                if (++count[0] > 10000) size[0] = Long.MAX_VALUE;
                return size[0] > 100L * 1024 * 1024 ? java.nio.file.FileVisitResult.TERMINATE
                        : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                if (exception instanceof java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE;
                throw exception;
            }
        });
        return size[0];
    }
}
