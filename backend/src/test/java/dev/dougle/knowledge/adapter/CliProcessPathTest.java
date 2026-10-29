package dev.dougle.knowledge.adapter;

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
                new dev.dougle.knowledge.application.CuratorSettings("", ""));
        assertThat(diagnostics.probe(dev.dougle.knowledge.application.CuratorModel.Provider.CODEX).status()).isEqualTo("AVAILABLE");
    }
    @Test
    void explicitCommandDirectorySupportsEnvShebangWithoutReadingShellProfiles() {
        var builder = new ProcessBuilder("/synthetic/bin/codex", "--version");
        builder.environment().put("PATH", "/usr/bin:/bin");
        CliProcessPath.includeCommandDirectory(builder);
        assertThat(builder.environment().get("PATH")).startsWith("/synthetic/bin:/usr/bin:/bin");
        assertThat(builder.environment().get("PATH").split(":" )).doesNotContain("", ".");
        assertThat(builder.command()).containsExactly("/synthetic/bin/codex", "--version");
    }

    @Test
    void bareCommandDoesNotAddCurrentWorkingDirectory() {
        var builder = new ProcessBuilder("codex");
        builder.environment().put("PATH", "/usr/bin:/bin");
        CliProcessPath.includeCommandDirectory(builder);
        assertThat(builder.environment().get("PATH")).isEqualTo("/usr/bin:/bin");
    }

    @Test
    void symlinkedNpmToolFindsItsOwnNodeWithFinderPath() throws Exception {
        var version = fixture.resolve(".nvm/versions/node/v22");
        var bin = java.nio.file.Files.createDirectories(version.resolve("bin"));
        var node = bin.resolve("node");
        java.nio.file.Files.writeString(node, "#!/bin/sh\nprintf 'synthetic-node-ok\\n'\n");
        assertThat(node.toFile().setExecutable(true, true)).isTrue();
        var packageDirectory = java.nio.file.Files.createDirectories(version.resolve("lib/node_modules/vendor/cli"));
        var script = packageDirectory.resolve("cli.js");
        java.nio.file.Files.writeString(script, "#!/usr/bin/env node\n");
        assertThat(script.toFile().setExecutable(true, true)).isTrue();
        var launcher = java.nio.file.Files.createDirectories(fixture.resolve(".local/bin")).resolve("claude-work");
        java.nio.file.Files.createSymbolicLink(launcher, script);
        var builder = new ProcessBuilder(launcher.toString(), "--version");
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        CliProcessPath.includeCommandDirectory(builder);
        assertThat(builder.environment().get("PATH").split(java.io.File.pathSeparator))
                .startsWith(launcher.getParent().toString(), packageDirectory.toRealPath().toString(), bin.toRealPath().toString());
        assertThat(builder.command()).containsExactly(launcher.toString(), "--version");
        var stderr = fixture.resolve("launcher-stderr");
        builder.redirectError(stderr.toFile());
        var process = builder.start();
        try {
            process.getOutputStream().close();
            assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as("Synthetic env/shebang launcher must exit within the fixture deadline").isTrue();
            assertThat(process.exitValue()).as(java.nio.file.Files.readString(stderr)).isZero();
            assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("synthetic-node-ok\n");
            assertThat(builder.environment().get("PATH")).contains(bin.toString());
        } finally {
            process.destroyForcibly();
            try {
                assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).as("Synthetic launcher cleanup").isTrue();
            } finally {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            }
        }
    }
}
