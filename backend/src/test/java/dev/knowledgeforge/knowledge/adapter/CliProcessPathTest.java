package dev.knowledgeforge.knowledge.adapter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CliProcessPathTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixture;

    @Test
    void actuallyFindsAnEnvInterpreterBesideTheSelectedExecutable() throws Exception {
        var interpreter = fixture.resolve("synthetic-forge-interpreter");
        java.nio.file.Files.writeString(interpreter, "#!/bin/sh\nprintf 'codex-cli 1.2.3\\n'\n");
        assertThat(interpreter.toFile().setExecutable(true, true)).isTrue();
        var executable = fixture.resolve("synthetic-cli");
        java.nio.file.Files.writeString(executable, "#!/usr/bin/env synthetic-forge-interpreter\n");
        assertThat(executable.toFile().setExecutable(true, true)).isTrue();
        var diagnostics = new CliDiagnosticsAdapter(executable.toString(), "unused",
                new dev.knowledgeforge.knowledge.application.CuratorSettings("", ""));
        assertThat(diagnostics.probe(dev.knowledgeforge.knowledge.application.CuratorModel.Provider.CODEX).status()).isEqualTo("AVAILABLE");
    }
    @Test
    void explicitCommandDirectorySupportsEnvShebangWithoutReadingShellProfiles() {
        var builder = new ProcessBuilder("/synthetic/bin/codex", "--version");
        builder.environment().put("PATH", "/usr/bin:/bin");
        CliProcessPath.includeCommandDirectory(builder);
        assertThat(builder.environment().get("PATH")).isEqualTo("/synthetic/bin:/usr/bin:/bin");
        assertThat(builder.command()).containsExactly("/synthetic/bin/codex", "--version");
    }

    @Test
    void bareCommandDoesNotAddCurrentWorkingDirectory() {
        var builder = new ProcessBuilder("codex");
        builder.environment().put("PATH", "/usr/bin:/bin");
        CliProcessPath.includeCommandDirectory(builder);
        assertThat(builder.environment().get("PATH")).isEqualTo("/usr/bin:/bin");
    }
}
