package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CuratorModel;
import dev.knowledgeforge.library.application.LibraryException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class CliCuratorModel implements CuratorModel {
    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["title","claims"],"properties":{
              "title":{"type":"string"},"claims":{"type":"array","items":{"type":"object",
              "additionalProperties":false,"required":["text","evidenceIds"],"properties":{
              "text":{"type":"string"},"evidenceIds":{"type":"array","items":{"type":"string"}}}}}}}
            """;
    private final String codex;
    private final String claude;
    private final Duration timeout;

    @Autowired
    public CliCuratorModel(@Value("${forge.ai.codex-command:codex}") String codex,
                           @Value("${forge.ai.claude-command:claude}") String claude) {
        this(codex, claude, Duration.ofSeconds(120));
    }

    CliCuratorModel(String codex, String claude, Duration timeout) {
        this.codex = codex;
        this.claude = claude;
        this.timeout = timeout;
    }

    @Override
    public String generate(Provider provider, String prompt) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("forge-curator-");
            Files.writeString(directory.resolve("schema.json"), SCHEMA);
            List<String> command = command(provider, directory);
            String stdout = run(command, directory, prompt);
            if (provider == Provider.CODEX) {
                Path output = directory.resolve("answer.json");
                if (!Files.isRegularFile(output) || Files.size(output) > 65536) throw new IOException();
                return Files.readString(output);
            }
            var result = JsonMapper.builder().build().readTree(stdout);
            if (!result.path("type").asString("").equals("result") || result.path("is_error").asBoolean(true)
                    || !result.path("subtype").asString("").equals("success") || !result.path("structured_output").isObject()) {
                throw new IOException();
            }
            return result.path("structured_output").toString();
        } catch (LibraryException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new LibraryException(502, "AI CLI 실행 실패: 설치 버전·터미널 로그인·사용 한도를 확인하세요. CLI 오류 원문은 노출하지 않습니다.");
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
        }
    }

    List<String> command(Provider provider, Path directory) {
        if (provider == Provider.CLAUDE) {
            return List.of(claude, "--print", "--output-format", "json", "--json-schema", SCHEMA,
                    "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}",
                    "--setting-sources", "", "--settings", "{\"disableAllHooks\":true}", "--disable-slash-commands",
                    "--no-session-persistence", "--no-chrome", "--permission-mode", "dontAsk");
        }
        List<String> arguments = new ArrayList<>(List.of(codex, "--no-daemon", "exec", "--ignore-user-config",
                "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only", "--color", "never",
                "--config", "approval_policy=\"never\"", "--config", "web_search=\"disabled\"",
                "--config", "project_doc_max_bytes=0", "--config", "tools.view_image=false",
                "--enable", "skip_host_skill_discovery",
                "--output-schema", directory.resolve("schema.json").toString(),
                "--output-last-message", directory.resolve("answer.json").toString()));
        for (String feature : List.of("shell_tool", "shell_snapshot", "apps", "plugins", "hooks", "multi_agent",
                "browser_use", "computer_use", "image_generation", "code_mode", "skill_search")) {
            arguments.addAll(List.of("--disable", feature));
        }
        arguments.add("-");
        return arguments;
    }

    private String run(List<String> command, Path directory, String prompt) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().retainAll(Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL",
                "SYSTEMROOT", "SystemRoot", "WINDIR", "CODEX_HOME"));
        Process process = builder.start();
        Set<ProcessHandle> descendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var stdout = executor.submit(() -> read(process.getInputStream(), process));
            var stderr = executor.submit(() -> read(process.getErrorStream(), process));
            var input = executor.submit(() -> {
                try (var stream = process.getOutputStream()) { stream.write(prompt.getBytes(StandardCharsets.UTF_8)); }
                return true;
            });
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(descendants::add);
                if (System.nanoTime() >= deadline) throw new LibraryException(504, "AI CLI 응답 제한 시간을 초과했습니다. 기존 지식은 유지됩니다.");
                Path output = directory.resolve("answer.json");
                if (Files.exists(output) && Files.size(output) > 65536) throw new IOException();
            }
            input.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            String errors = stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            String output = stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                String diagnostic = (errors + output).toLowerCase(java.util.Locale.ROOT);
                if (diagnostic.contains("not logged in") || diagnostic.contains("invalid api key")
                        || diagnostic.contains("authentication_error")) {
                    throw new LibraryException(503, "AI CLI 인증이 필요합니다. 앱과 같은 OS 계정의 터미널에서 CLI에 로그인하세요.");
                }
                throw new LibraryException(502, "AI CLI가 종료 코드 " + process.exitValue()
                        + "로 실패했습니다. 설치 버전·로그인·사용 한도를 확인하세요.");
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LibraryException(503, "AI CLI 실행이 중단되었습니다.");
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new LibraryException(504, "AI CLI 응답 제한 시간을 초과했습니다.");
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("CLI I/O failed");
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
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
            } finally {
                executor.shutdownNow();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private String read(InputStream stream, Process process) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 262144) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("CLI output limit");
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
