package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CliLaunchStore;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.CuratorSettings;
import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisabledOnOs(OS.WINDOWS)
class CodexProfileTest {
    @TempDir Path directory;

    @Test
    void preparesOnlyPrivateDedicatedProfilesAndNeverRepairsUnsafeContent() throws Exception {
        Path profile = CodexProfile.prepare(directory.resolve("profile").toString());
        assertThat(Files.getPosixFilePermissions(profile)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThatThrownBy(() -> CodexProfile.validate(profile, true)).isInstanceOf(java.io.IOException.class);
        Files.writeString(profile.resolve("config.toml"), "synthetic settings");
        assertThatThrownBy(() -> CodexProfile.prepare(profile.toString())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(profile.resolve("config.toml"))).isEqualTo("synthetic settings");
        assertThatThrownBy(() -> CodexProfile.prepare("relative")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> CodexProfile.prepare(Path.of(System.getProperty("user.home"), ".codex").toString()))
                .isInstanceOf(java.io.IOException.class);
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, profile);
        assertThatThrownBy(() -> CodexProfile.prepare(link.toString())).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void authenticationLoginDraftAndStreamingShareOneProfileWithoutCopyingCredentials() throws Exception {
        Path profile = directory.resolve("profile");
        Path executable = directory.resolve("fake-provider");
        Path stream = Path.of("../scripts/fixtures/fake-codex-app-server.mjs").toRealPath();
        Files.writeString(executable, """
                #!/bin/sh
                [ "$HOME" = "$PWD" ] || exit 91
                [ "$HOME" != "$CODEX_HOME" ] || exit 92
                case "$*" in
                  *app-server*) exec node '%s' "$@" ;;
                  *'login status')
                    if [ -f "$CODEX_HOME/auth.json" ]; then echo 'Logged in using ChatGPT' >&2; exit 0; fi
                    echo 'Not logged in' >&2; exit 1 ;;
                  *login)
                    [ "$*" = '--config cli_auth_credentials_store="file" login' ] || exit 93
                    umask 077
                    mkdir -p "$CODEX_HOME/log"
                    printf 'synthetic login log' > "$CODEX_HOME/log/codex-login.log"
                    printf '{"synthetic":true}' > "$CODEX_HOME/auth.json"
                    exit 0 ;;
                  *exec*)
                    [ -f "$CODEX_HOME/auth.json" ] || exit 94
                    cat >/dev/null
                    printf '{"title":"synthetic","claims":[]}' > answer.json
                    exit 0 ;;
                esac
                exit 95
                """.formatted(stream));
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var launch = mock(CliLaunchConfiguration.class);
        when(launch.command(any())).thenReturn(executable.toString());
        var connection = new ProviderConnectionAdapter(launch, Duration.ofSeconds(5), profile.toString());
        assertThat(connection.check(Provider.CODEX).state()).isEqualTo("LOGIN_REQUIRED");
        assertThat(profile.resolve("auth.json")).doesNotExist();
        var login = new ProviderLoginAdapter(launch, connection, Duration.ofSeconds(5), profile.toString());
        try {
            assertThatThrownBy(() -> login.start(Provider.CODEX, false)).isInstanceOf(LibraryException.class);
            var started = login.start(Provider.CODEX, true);
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (login.status(started.id()).state().equals("PENDING") && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(login.status(started.id()).state()).isEqualTo("AUTHENTICATED");
            assertThat(connection.check(Provider.CODEX).state()).isEqualTo("AUTHENTICATED");
            var model = new CliCuratorModel(executable.toString(), "unused", Duration.ofSeconds(8), new CuratorSettings("", ""), profile.toString());
            assertThat(model.generate(Provider.CODEX, "synthetic", "")).contains("synthetic");
            Files.writeString(profile.resolve(".sandbox_migration"), "synthetic");
            var chunks = new java.util.concurrent.CopyOnWriteArrayList<String>();
            assertThat(model.generateWithText(Provider.CODEX, "synthetic", "", chunks::add)).contains("claims");
            assertThat(chunks).isNotEmpty();
            assertThat(Files.readString(profile.resolve("auth.json"))).isEqualTo("{\"synthetic\":true}");
        } finally { login.close(); }
    }

    @Test
    void acceptsOnlyPrivateRegularProviderLoginLogAndNeverReadsItsContents() throws Exception {
        Path profile = CodexProfile.prepare(directory.resolve("profile").toString());
        Path log = Files.createDirectory(profile.resolve("log")).resolve("codex-login.log");
        Files.writeString(log, "synthetic private output");
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-------"));
        assertThat(CodexProfile.validate(profile, false)).isEqualTo(profile.toRealPath());
        Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
        Files.delete(log);
        Files.createSymbolicLink(log, directory.resolve("outside"));
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
        Files.delete(log);
        Files.writeString(log.resolveSibling("config.toml"), "synthetic");
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void permitsOnlyTheRegularSqliteMaintenanceLockWithoutReadingOrChangingIt() throws Exception {
        Path profile = CodexProfile.prepare(directory.resolve("profile").toString());
        Path lock = profile.resolve(".sqlite-maintenance.lock");
        Files.writeString(lock, "synthetic lock contents");
        assertThat(CodexProfile.validate(profile, false)).isEqualTo(profile.toRealPath());
        assertThat(Files.readString(lock)).isEqualTo("synthetic lock contents");
        Files.delete(lock);
        Files.createSymbolicLink(lock, directory.resolve("outside"));
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
        Files.delete(lock);
        Files.createDirectory(lock);
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
        Files.delete(lock);
        Files.writeString(profile.resolve(".sqlite-maintenance.toml"), "synthetic config");
        assertThatThrownBy(() -> CodexProfile.validate(profile, false)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void profileChangeInvalidatesCodexApprovalBindingWithoutChangingClaude() {
        var store = mock(CliLaunchStore.class);
        when(store.current()).thenReturn(new CliLaunchStore.Configuration("", "", 0));
        var ordinary = new CliLaunchConfiguration(store, "codex", "claude");
        var privateProfile = new CliLaunchConfiguration(store, "codex", "claude", directory.resolve("profile").toString());
        assertThat(privateProfile.binding(Provider.CODEX)).isNotEqualTo(ordinary.binding(Provider.CODEX));
        assertThat(privateProfile.binding(Provider.CLAUDE)).isEqualTo(ordinary.binding(Provider.CLAUDE));
    }
}
