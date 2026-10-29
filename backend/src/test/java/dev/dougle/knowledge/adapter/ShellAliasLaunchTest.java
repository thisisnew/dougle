package dev.dougle.knowledge.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.CuratorSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShellAliasLaunchTest {
    @TempDir Path home;

    Path binary(String name) throws Exception {
        Path executable = home.resolve("bin").resolve(name);
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\n"
                + "printf '%s\\n' \"$*\" >> '" + home.resolve("invocations") + "'\n"
                + "[ \"$CLAUDE_CONFIG_DIR\" = '" + home.resolve("Work Profile") + "' ] || exit 41\n"
                + "if [ \"$*\" = 'auth --help' ]; then echo '  status Show authentication status'; exit 0; fi\n"
                + "if [ \"$*\" = 'auth status' ]; then echo '{\"loggedIn\":true}'; exit 0; fi\n"
                + "echo profile-preserved\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    @Test void profileAliasSurvivesDiscoveryAuthenticationAndInferenceWithoutShellExecution() throws Exception {
        binary("claude");
        Files.writeString(home.resolve(".zshrc"), "touch '" + home.resolve("must-not-run") + "'\n"
                + "alias office='CLAUDE_CONFIG_DIR=\"$HOME/Work Profile\" claude'\n");
        var discovery = new InstalledCliDiscovery(home, List.of());
        String command = discovery.defaultCommand(Provider.CLAUDE, "claude");
        assertThat(command).startsWith("dougle-alias-");
        assertThat(CompanyExecutable.resolve(command)).isEqualTo(command);
        var launch = mock(CliLaunchConfiguration.class);
        when(launch.command(Provider.CLAUDE)).thenReturn(command);
        var checked = new ProviderConnectionAdapter(launch, "").check(Provider.CLAUDE);
        assertThat(checked.state()).as("%s; fixture invocations: %s", checked, invocations())
                .isEqualTo("AUTHENTICATED");
        assertThat(new CliCuratorModel("codex", command, new CuratorSettings("", ""))
                .run(List.of(command, "--print"), home, "", Duration.ofSeconds(30))).contains("profile-preserved");
        assertThat(home.resolve("must-not-run")).doesNotExist();
    }

    @Test void aliasEnvironmentStaysOutOfCandidatePathsAndIsNotForwardedToOtherCommands() throws Exception {
        Path executable = binary("claude");
        String command = ShellAliasLaunch.register("claude", "work", "ANTHROPIC_AUTH_TOKEN=synthetic-secret "
                + executable, home, List.of());
        assertThat(command).isNotNull().doesNotContain("synthetic-secret");
        var builder = new ProcessBuilder(command, "auth", "status");
        ShellAliasLaunch.apply(builder);
        assertThat(builder.command()).containsExactly(executable.toString(), "auth", "status");
        assertThat(builder.environment()).containsEntry("ANTHROPIC_AUTH_TOKEN", "synthetic-secret");
        var normal = new ProcessBuilder(executable.toString(), "auth", "status");
        normal.environment().clear();
        ShellAliasLaunch.apply(normal);
        assertThat(normal.environment()).isEmpty();
        String changed = ShellAliasLaunch.register("claude", "work", "ANTHROPIC_AUTH_TOKEN=different "
                + executable, home, List.of());
        assertThat(changed).isNotEqualTo(command);
    }

    @Test void browserLoginAndItsRecheckUseTheSameAliasProfile() throws Exception {
        Path executable = binary("claude");
        Path marker = home.resolve("logged-in");
        Files.writeString(executable, "#!/bin/sh\n"
                + "printf '%s\\n' \"$*\" >> '" + home.resolve("invocations") + "'\n"
                + "[ \"$CLAUDE_CONFIG_DIR\" = '" + home.resolve("Work Profile") + "' ] || exit 41\n"
                + "case \"$*\" in\n"
                + "'auth --help') echo '  status Show authentication status';;\n"
                + "'auth login') : > '" + marker + "';;\n"
                + "'auth status') if [ -f '" + marker + "' ]; then echo '{\"loggedIn\":true}';"
                + " else echo '{\"loggedIn\":false}'; exit 1; fi;;\n*) exit 99;;\nesac\n");
        String command = ShellAliasLaunch.register("claude", "office", "CLAUDE_CONFIG_DIR=\"$HOME/Work Profile\" claude",
                home, List.of(executable.getParent()));
        var launch = mock(CliLaunchConfiguration.class);
        when(launch.command(Provider.CLAUDE)).thenReturn(command);
        var connection = new ProviderConnectionAdapter(launch, "");
        var login = new ProviderLoginAdapter(launch, connection, Duration.ofSeconds(30));
        try {
            var checked = connection.check(Provider.CLAUDE);
            assertThat(checked.state()).as("%s; fixture invocations: %s", checked, invocations()).isEqualTo("LOGIN_REQUIRED");
            String id = login.start(Provider.CLAUDE, true).id();
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (login.status(id).state().equals("PENDING") && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(login.status(id).state()).as("Fixture invocations: %s", invocations()).isEqualTo("AUTHENTICATED");
            assertThat(marker).exists();
            assertThat(Files.readAllLines(home.resolve("invocations"))).containsExactly(
                    "auth --help", "auth status", "auth --help", "auth status", "auth login", "auth --help", "auth status");
        } finally { login.close(); }
    }

    private String invocations() throws Exception {
        Path trace = home.resolve("invocations");
        return Files.exists(trace) ? Files.readString(trace) : "NOT_STARTED";
    }

    @ParameterizedTest
    @ValueSource(strings = {"NODE_OPTIONS=--require=evil", "PATH=/tmp", "HOME=/tmp", "TOKEN=secret",
            "CLAUDE_CONFIG_DIR=$(touch marker)", "CLAUDE_CONFIG_DIR=`touch marker`",
            "CLAUDE_CONFIG_DIR=$UNKNOWN", "CLAUDE_CONFIG_DIR=relative", "CLAUDE_CONFIG_DIR=/tmp;touch marker",
            "CLAUDE_CONFIG_DIR=/tmp &&", "CLAUDE_CONFIG_DIR='/tmp", "CLAUDE_CONFIG_DIR=/tmp\\"})
    void rejectsShellEvaluationUnknownEnvironmentAndBrokenSyntax(String prefix) throws Exception {
        Path executable = binary("claude");
        assertThat(ShellAliasLaunch.register("claude", "work", prefix + " " + executable, home, List.of())).isNull();
    }

    @Test void rejectsExtraArgumentsAndProviderMismatch() throws Exception {
        Path executable = binary("claude");
        assertThat(ShellAliasLaunch.register("claude", "work", "CLAUDE_CONFIG_DIR=/tmp " + executable
                + " --dangerously-skip-permissions", home, List.of())).isNull();
        assertThat(ShellAliasLaunch.register("codex", "work", "OPENAI_API_KEY=synthetic " + executable,
                home, List.of())).isNull();
        assertThatThrownBy(() -> ShellAliasLaunch.apply(new ProcessBuilder("dougle-alias-missing")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void codexAliasRetainsDedicatedHomeWhileApplyingOnlyAllowedEnvironment() throws Exception {
        Path executable = binary("codex");
        String command = ShellAliasLaunch.register("codex", "office", "env OPENAI_API_KEY=synthetic " + executable,
                home, List.of());
        var builder = new ProcessBuilder(command, "login", "status");
        builder.environment().put("CODEX_HOME", "/dedicated");
        ShellAliasLaunch.apply(builder);
        assertThat(builder.command().getFirst()).isEqualTo(executable.toString());
        assertThat(builder.environment()).containsEntry("CODEX_HOME", "/dedicated").containsEntry("OPENAI_API_KEY", "synthetic");
    }

    @Test void multipleAccountAliasesDoNotSelectAnArbitraryAccount() throws Exception {
        Path executable = binary("claude");
        Files.writeString(home.resolve(".zshrc"), "alias office='CLAUDE_CONFIG_DIR=~/office claude'\n"
                + "alias personal='CLAUDE_CONFIG_DIR=~/personal claude'\n");
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertThat(discovery.defaultCommand(Provider.CLAUDE, "claude")).isEqualTo(executable.toString());
        assertThat(discovery.find(Provider.CLAUDE)).hasSize(3);
    }
}
