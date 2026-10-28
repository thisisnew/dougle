package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CuratorModel;
import dev.dougle.knowledge.application.CuratorSettings;
import dev.dougle.knowledge.application.ProviderExecutionException;
import dev.dougle.knowledge.application.ProviderExecutionException.Transport;
import dev.dougle.library.application.LibraryException;
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
    static final String PLAN_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["decision","query","candidate"],"properties":{
              "decision":{"type":"string","enum":["SEARCH","STOP","PROPOSE_MYSQL_READ"]},
              "query":{"type":["string","null"],"maxLength":256},
              "candidate":{"anyOf":[{"type":"null"},
                {"type":"object","additionalProperties":false,"required":["table","columns","equalities","limit"],"properties":{
                  "table":{"type":["string","null"]},"columns":{"type":["array","null"],"items":{"type":"string"}},
                  "equalities":{"type":["array","null"],"items":{"type":"object","additionalProperties":false,
                    "required":["column","value"],"properties":{"column":{"type":"string"},"value":{"type":"string"}}}},
                  "limit":{"type":["integer","null"]}}}
              ]}}}
            """;
    private final String codex;
    private final String claude;
    private final Duration timeout;
    private final CuratorSettings settings;
    private final String codexStreamHome;

    @Autowired
    public CliCuratorModel(dev.dougle.knowledge.application.CliLaunchConfiguration launch, CuratorSettings settings,
                           @Value("${forge.ai.codex-stream-home:}") String codexStreamHome) {
        this(launch.command(Provider.CODEX), launch.command(Provider.CLAUDE), Duration.ofSeconds(120), settings, codexStreamHome);
    }

    CliCuratorModel(String codex, String claude) {
        this(codex, claude, Duration.ofSeconds(120));
    }

    CliCuratorModel(String codex, String claude, CuratorSettings settings) {
        this(codex, claude, Duration.ofSeconds(120), settings);
    }

    CliCuratorModel(String codex, String claude, Duration timeout) {
        this(codex, claude, timeout, new CuratorSettings("", ""));
    }

    CliCuratorModel(String codex, String claude, Duration timeout, CuratorSettings settings) {
        this(codex, claude, timeout, settings, "");
    }

    CliCuratorModel(String codex, String claude, Duration timeout, CuratorSettings settings, String codexStreamHome) {
        this.codex = codex;
        this.claude = claude;
        this.timeout = timeout;
        this.settings = settings;
        this.codexStreamHome = codexStreamHome;
    }

    public String generate(Provider provider, String prompt) {
        return generate(provider, prompt, settings.requestedModel(provider));
    }

    @Override
    public String generate(Provider provider, String prompt, String requestedModel) {
        return generateInternal(provider, prompt, requestedModel, null);
    }

    @Override
    public String generatePlan(Provider provider, String prompt, String requestedModel) {
        return generateInternal(provider, prompt, requestedModel, null, PLAN_SCHEMA);
    }

    @Override
    public String generateWithText(Provider provider, String prompt, String requestedModel, java.util.function.Consumer<String> text) {
        if (provider == Provider.CODEX && !codexStreamHome.isBlank()) {
            return new CodexAppServer(codex, Path.of(codexStreamHome), timeout)
                    .generate(prompt, CuratorSettings.normalize(requestedModel), text);
        }
        return generateInternal(provider, prompt, requestedModel, provider == Provider.CLAUDE ? text : null);
    }

    @Override
    public String generateWithImages(Provider provider, String prompt, String requestedModel,
                                    List<dev.dougle.knowledge.application.ModelImage> images, java.util.function.Consumer<String> text) {
        if (images.isEmpty()) return generateWithText(provider, prompt, requestedModel, text);
        if (provider != Provider.CODEX || codexStreamHome.isBlank()) {
            throw new LibraryException(400, "이미지 질문은 앱에 포함된 Codex 연결을 사용해 주세요.");
        }
        return new CodexAppServer(codex, Path.of(codexStreamHome), timeout)
                .generate(prompt, CuratorSettings.normalize(requestedModel), text, images);
    }

    private String generateInternal(Provider provider, String prompt, String requestedModel, java.util.function.Consumer<String> text) {
        return generateInternal(provider, prompt, requestedModel, text, SCHEMA);
    }

    private String generateInternal(Provider provider, String prompt, String requestedModel,
                                    java.util.function.Consumer<String> text, String schema) {
        String selectedModel = CuratorSettings.normalize(requestedModel);
        Path directory = null;
        try {
            directory = Files.createTempDirectory("forge-curator-");
            Files.writeString(directory.resolve("schema.json"), schema);
            List<String> command = command(provider, directory, selectedModel, schema);
            ClaudeTextStream stream = text == null ? null : new ClaudeTextStream(text);
            if (stream != null) {
                command.set(command.indexOf("--output-format") + 1, "stream-json");
                command.addAll(List.of("--verbose", "--include-partial-messages"));
            }
            String stdout = run(command, directory, prompt, timeout, stream);
            if (stream != null) return stream.result();
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
        return command(provider, directory, settings.requestedModel(provider));
    }

    List<String> command(Provider provider, Path directory, String requestedModel) {
        return command(provider, directory, requestedModel, SCHEMA);
    }

    private List<String> command(Provider provider, Path directory, String requestedModel, String schema) {
        if (provider == Provider.CLAUDE) {
            List<String> arguments = new ArrayList<>(List.of(claude, "--print", "--output-format", "json", "--json-schema", schema,
                    "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config", "--mcp-config", "{\"mcpServers\":{}}",
                    "--setting-sources", "", "--settings", "{\"disableAllHooks\":true}", "--disable-slash-commands",
                    "--no-session-persistence", "--no-chrome", "--permission-mode", "dontAsk"));
            addModel(arguments, requestedModel);
            return arguments;
        }
        List<String> arguments = new ArrayList<>(List.of(codex, "--no-daemon", "exec", "--ignore-user-config",
                "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only", "--color", "never",
                "--config", "approval_policy=\"never\"", "--config", "web_search=\"disabled\"",
                "--config", "project_doc_max_bytes=0", "--config", "tools.view_image=false",
                "--enable", "skip_host_skill_discovery",
                "--output-schema", directory.resolve("schema.json").toString(),
                "--output-last-message", directory.resolve("answer.json").toString()));
        if (!codexStreamHome.isBlank()) arguments.addAll(List.of("--config", "cli_auth_credentials_store=\"file\""));
        if (schema.equals(PLAN_SCHEMA)) arguments.add("--json");
        for (String feature : List.of("shell_tool", "shell_snapshot", "apps", "plugins", "hooks", "multi_agent",
                "browser_use", "computer_use", "image_generation", "code_mode", "skill_search")) {
            arguments.addAll(List.of("--disable", feature));
        }
        addModel(arguments, requestedModel);
        arguments.add("-");
        return arguments;
    }

    private void addModel(List<String> arguments, String requestedModel) {
        if (!requestedModel.isEmpty()) arguments.addAll(List.of("--model", requestedModel));
    }

    private String run(List<String> command, Path directory, String prompt) throws IOException {
        return run(command, directory, prompt, timeout);
    }

    String run(List<String> command, Path directory, String prompt, Duration limit) throws IOException {
        return run(command, directory, prompt, limit, null);
    }

    private String run(List<String> command, Path directory, String prompt, Duration limit, ClaudeTextStream stream) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().keySet().retainAll(Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL",
                "SYSTEMROOT", "SystemRoot", "WINDIR", "CODEX_HOME"));
        CliProcessPath.includeCommandDirectory(builder);
        if (!codexStreamHome.isBlank() && command.contains("--output-last-message")) {
            CodexProfile.configure(builder, CodexProfile.validate(Path.of(codexStreamHome), true), directory);
        }
        Process process = builder.start();
        var diagnostics = new ProcessDiagnostics(command.contains("--output-last-message") ? Transport.CODEX_EXEC : Transport.CLI_EXEC);
        Set<ProcessHandle> descendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var executor = Executors.newFixedThreadPool(3);
        try {
            var stdout = executor.submit(() -> stream == null ? read(process.getInputStream(), process, diagnostics::stdout) : readStream(process.getInputStream(), stream));
            var stderr = executor.submit(() -> read(process.getErrorStream(), process, diagnostics::stderr));
            var input = executor.submit(() -> {
                try (var stdin = process.getOutputStream()) { stdin.write(prompt.getBytes(StandardCharsets.UTF_8)); }
                diagnostics.promptWritten();
                return true;
            });
            long deadline = System.nanoTime() + limit.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (stdout.isDone()) stdout.get();
                process.descendants().forEach(descendants::add);
                if (System.nanoTime() >= deadline) throw new ProviderExecutionException(504, "AI CLI 응답 제한 시간을 초과했습니다. 기존 지식은 유지됩니다.", diagnostics.snapshot());
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
            throw new ProviderExecutionException(503, "AI CLI 실행이 중단되었습니다.", diagnostics.snapshot());
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new ProviderExecutionException(504, "AI CLI 응답 제한 시간을 초과했습니다.", diagnostics.snapshot());
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

    private String read(InputStream stream, Process process, java.util.function.ObjIntConsumer<byte[]> observer) throws IOException {
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
            observer.accept(buffer, count);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String readStream(InputStream input, ClaudeTextStream stream) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int total = 0;
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > 262144) throw new IOException("CLI stream limit");
            for (int offset = 0; offset < count; offset++) {
                if (buffer[offset] == '\n') {
                    stream.accept(line.toString(StandardCharsets.UTF_8)); line.reset();
                } else {
                    if (line.size() >= 65536) throw new IOException("CLI line limit");
                    line.write(buffer[offset]);
                }
            }
        }
        if (line.size() > 0) stream.accept(line.toString(StandardCharsets.UTF_8));
        return "";
    }
}
