package dev.dougle.sources.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GitRunnerTest {
    @Test
    void authenticationIsScopedAndAbsentFromArgumentsAndGlobalConfiguration() throws Exception {
        String token = "github_pat_synthetic_12345678901234567890";
        var command = new GitRunner().authenticated(token).command(Path.of("/tmp"), "rev-parse", "HEAD");
        assertThat(command.command().toString()).doesNotContain(token).doesNotContain("Authorization");
        assertThat(command.environment()).containsEntry("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
        assertThat(command.environment()).containsEntry("GIT_CONFIG_GLOBAL", "/dev/null");
        assertThat(new GitRunner().command(Path.of("/tmp"), "rev-parse", "HEAD").environment()).doesNotContainKey("GIT_CONFIG_VALUE_0");
        assertThatThrownBy(() -> new GitRunner().authenticated(token).command(Path.of("/tmp"), "push", "origin", "HEAD"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @TempDir Path directory;

    private Path bundle() throws Exception {
        Path root = Files.createDirectory(directory.resolve("내장 Git"));
        for (String relative : List.of("bin/git", "libexec/git-core/git", "libexec/git-core/git-remote-https")) {
            Path binary = root.resolve(relative);
            Files.createDirectories(binary.getParent());
            Files.writeString(binary, "#!/bin/sh\nprintf 'bundled fixture'\n");
            assertThat(binary.toFile().setExecutable(true)).isTrue();
        }
        return root;
    }

    @Test
    void executesSelectedBundleWithSpacesAndKoreanWithoutShellOrPathLookup() throws Exception {
        Path root = bundle();
        GitRunner runner = new GitRunner(root.toString());
        ProcessBuilder command = runner.command(directory, "--version");
        assertThat(command.command()).startsWith(root.toRealPath().resolve("bin/git").toString(),
                "--exec-path=" + root.toRealPath().resolve("libexec/git-core"));
        command.environment().put("PATH", "/nonexistent");
        Process process = command.start();
        assertThat(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("bundled fixture");
        assertThat(process.waitFor()).isZero();
    }

    @Test
    void missingOrEscapingBundleCannotFallBackToSystemGit() throws Exception {
        assertThatThrownBy(() -> new GitRunner("relative")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitRunner(directory.resolve("missing").toString()).command(directory))
                .isInstanceOf(java.io.IOException.class);
        Path root = bundle();
        Path helper = root.resolve("libexec/git-core/git-remote-https");
        Files.delete(helper);
        Files.createSymbolicLink(helper, Path.of("/bin/sh"));
        assertThatThrownBy(() -> new GitRunner(root.toString()).command(directory))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("내장 Git");
    }

    @Test
    void inheritedGitOverridesCannotRedirectHelpersOrInjectConfiguration() {
        var environment = new java.util.HashMap<>(java.util.Map.of(
                "GIT_EXEC_PATH", "/outside/helpers", "GIT_CONFIG_PARAMETERS", "'http.sslVerify'='false'",
                "GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "core.hooksPath", "GIT_CONFIG_VALUE_0", "/outside/hooks",
                "GIT_SSH_COMMAND", "unexpected-command", "GIT_CONFIG_GLOBAL", "/outside/config",
                "GIT_TEMPLATE_DIR", "/outside/templates", "PATH", "/fixture/bin"));
        GitRunner.restrictEnvironment(environment);
        assertThat(environment).containsOnlyKeys("PATH", "GIT_TERMINAL_PROMPT", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL");
        assertThat(environment).containsEntry("PATH", "/fixture/bin").containsEntry("GIT_CONFIG_NOSYSTEM", "1");
        assertThat(environment.get("GIT_CONFIG_GLOBAL")).isNotEqualTo("/outside/config");
    }

    @Test
    void keepsReadOnlyTransportDefaultsAndOnlyControlledGitEnvironment() throws Exception {
        ProcessBuilder command = new GitRunner().command(directory, "rev-parse", "HEAD");
        assertThat(command.command()).contains("credential.helper=", "protocol.file.allow=never", "protocol.ext.allow=never",
                "http.followRedirects=false", "http.sslVerify=true", "core.fsmonitor=false", "init.templateDir=");
        assertThat(command.environment().keySet().stream().filter(key -> key.startsWith("GIT_")).toList())
                .containsExactlyInAnyOrder("GIT_TERMINAL_PROMPT", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL");
        assertThat(command.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    @Test
    void onlyExactCollectionCommandsCanReachProcessCreation() throws Exception {
        var runner = new GitRunner();
        String object = "a".repeat(40);
        for (String[] arguments : List.of(
                new String[] {"ls-tree", "-rz", object},
                new String[] {"cat-file", "-s", object},
                new String[] {"cat-file", "blob", object},
                new String[] {"clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1",
                        "--single-branch", "--", "https://github.com/synthetic/fixture.git", "repository"})) {
            assertThat(runner.command(directory, arguments).command()).endsWith(arguments);
        }
    }

    @Test
    void rejectsWritesAliasesHelpersAndUncontrolledCloneArgumentsBeforeLaunch() {
        var runner = new GitRunner();
        for (String[] arguments : List.of(
                new String[] {"push", "origin", "HEAD"}, new String[] {"commit", "-am", "change"},
                new String[] {"config", "alias.read", "!touch marker"}, new String[] {"reset", "--hard"},
                new String[] {"-c", "alias.read=!touch marker", "read"},
                new String[] {"cat-file", "--filters", "HEAD:file"}, new String[] {"rev-parse", "--git-dir"},
                new String[] {"clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1",
                        "--single-branch", "--", "https://github.com.attacker.test/synthetic/fixture.git", "repository"},
                new String[] {"clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1",
                        "--single-branch", "--", "https://github.com/synthetic/fixture.git", "../original"})) {
            assertThatThrownBy(() -> runner.command(directory, arguments))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("read-only");
        }
    }
}
