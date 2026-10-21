package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CliDiagnostics;
import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.CuratorSettings;
import dev.knowledgeforge.library.application.LibraryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CliDiagnosticsAdapter implements CliDiagnostics {
    private final String codex;
    private final String claude;
    private final CuratorSettings settings;
    private final CliCuratorModel runner;
    private final Semaphore slot = new Semaphore(1);

    public CliDiagnosticsAdapter(@Value("${forge.ai.codex-command:codex}") String codex,
                                 @Value("${forge.ai.claude-command:claude}") String claude, CuratorSettings settings) {
        this.codex = codex;
        this.claude = claude;
        this.settings = settings;
        this.runner = new CliCuratorModel(codex, claude, settings);
    }

    @Override
    public Status probe(Provider provider) {
        if (!slot.tryAcquire()) throw new LibraryException(409, "CLI 설치 확인이 진행 중입니다.");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("forge-cli-probe-");
            String output = runner.run(List.of(provider == Provider.CODEX ? codex : claude, "--version"),
                    directory, "", Duration.ofSeconds(5)).strip();
            if (output.length() > 128) return status(provider, "UNRECOGNIZED", null);
            String pattern = provider == Provider.CODEX
                    ? "codex-cli ([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)"
                    : "([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?) \\(Claude Code\\)";
            var match = java.util.regex.Pattern.compile(pattern).matcher(output);
            if (match.matches()) return status(provider, "AVAILABLE", match.group(1));
            return status(provider, "UNRECOGNIZED", null);
        } catch (IOException | LibraryException exception) {
            return status(provider, "UNAVAILABLE", null);
        } finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            }
            slot.release();
        }
    }

    private Status status(Provider provider, String status, String version) {
        return new Status(provider.name(), status, version, settings.requestedModel(provider), "UNKNOWN", "NOT_CHECKED");
    }
}
