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
import org.springframework.stereotype.Component;

@Component
public class GitRunner {
    public byte[] run(Path directory, int limit, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + directory.resolve("disabled-hooks"),
                "-c", "credential.helper=", "-c", "protocol.file.allow=never", "-c", "protocol.ext.allow=never",
                "-c", "http.followRedirects=false", "-c", "http.sslVerify=true"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        builder.environment().remove("GIT_DIR");
        builder.environment().remove("GIT_WORK_TREE");
        builder.environment().remove("GIT_CONFIG_COUNT");
        Process process = builder.start();
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
