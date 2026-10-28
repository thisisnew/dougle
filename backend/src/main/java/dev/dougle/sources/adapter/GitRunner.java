package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
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
    private String credential;

    public GitRunner authenticated(String token) {
        if (token == null) return this;
        if (!token.matches("[A-Za-z0-9_]{20,255}")) throw new IllegalArgumentException("Invalid GitHub credential");
        var runner = new GitRunner(bundledHome == null ? "" : bundledHome.toString());
        runner.credential = token;
        return runner;
    }

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
        validateReadCommand(arguments);
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        restrictEnvironment(builder.environment());
        if (credential != null) {
            builder.environment().put("GIT_CONFIG_COUNT", "1");
            builder.environment().put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
            builder.environment().put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + java.util.Base64.getEncoder()
                    .encodeToString(("x-access-token:" + credential).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }
        if (bundledHome != null) {
            String inheritedPath = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", Path.of(command.getFirst()).getParent()
                    + (inheritedPath.isBlank() ? "" : java.io.File.pathSeparator + inheritedPath));
        }
        return builder;
    }

    private static void validateReadCommand(String[] arguments) {
        if (arguments == null || java.util.Arrays.stream(arguments).anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Unsupported read-only Git command");
        }
        var values = List.of(arguments);
        boolean allowed = values.equals(List.of("--version")) || values.equals(List.of("rev-parse", "HEAD"));
        if (arguments.length == 3 && arguments[2].matches("[a-f0-9]{40}")) {
            allowed |= arguments[0].equals("ls-tree") && arguments[1].equals("-rz")
                    || arguments[0].equals("cat-file") && List.of("-s", "blob").contains(arguments[1]);
        }
        if (arguments.length == 9) {
            allowed |= values.subList(0, 7).equals(List.of("clone", "--quiet", "--no-checkout", "--filter=blob:none",
                    "--depth=1", "--single-branch", "--"))
                    && arguments[7].matches("https://github\\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+\\.git")
                    && arguments[8].equals("repository");
        }
        if (!allowed) throw new IllegalArgumentException("Unsupported read-only Git command");
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
                long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    dev.dougle.ingestion.application.ProcessingProgress.report("READING", "GitHub", 0, 0);
                    if (System.nanoTime() > deadline || diskUsage(directory) > CollectionLimits.MAX_SNAPSHOT) {
                        throw new LibraryException(413, "Git 수집 시간(10분) 또는 임시 저장소(8 GiB) 한도를 초과했습니다.");
                    }
                }
                byte[] bytes = output.get();
                errors.get();
                if (process.exitValue() != 0) throw new IOException("GitHub 수집 실패 · 연결 인증, 회사 조직 승인과 네트워크를 확인하고 다시 동기화하세요.");
                if (bytes.length > limit) throw new LibraryException(413, "Git 수집 결과가 제한을 초과했습니다.");
                return bytes;
            } finally {
                process.destroyForcibly();
            }
        }
    }

    public dev.dougle.sources.domain.CollectedSnapshot.File readBlob(Path directory, String path,
                                                                            String object, long size) throws Exception {
        Path output = Files.createTempFile("dougle-git-blob-", ".bin");
        Process process = null;
        try {
            process = command(directory, "cat-file", "blob", object)
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                dev.dougle.ingestion.application.ProcessingProgress.report("READING", path, 0, 0);
                if (System.nanoTime() > deadline || Files.size(output) > size) {
                    throw new IOException("Git blob size or time limit exceeded");
                }
            }
            if (process.exitValue() != 0 || Files.size(output) != size) throw new IOException("Git blob read failed");
            try (var input = Files.newInputStream(output)) {
                return dev.dougle.sources.domain.CollectedSnapshot.File.copy(path, input, CollectionLimits.MAX_DOCUMENT);
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(output);
        }
    }

    private long diskUsage(Path directory) throws IOException {
        long[] size = {0};
        int[] count = {0};
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                size[0] += attributes.size();
                if (++count[0] > 100000) size[0] = Long.MAX_VALUE;
                return size[0] > CollectionLimits.MAX_SNAPSHOT ? java.nio.file.FileVisitResult.TERMINATE
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
