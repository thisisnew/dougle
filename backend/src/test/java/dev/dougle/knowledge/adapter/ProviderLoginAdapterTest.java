package dev.dougle.knowledge.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.knowledge.application.ProviderConnection;
import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class ProviderLoginAdapterTest {
    @TempDir Path directory;
    final ProviderConnection connection = mock(ProviderConnection.class);
    final CliLaunchConfiguration launch = mock(CliLaunchConfiguration.class);

    ProviderLoginAdapter fixture(String body, Duration timeout) throws Exception {
        Path command = directory.resolve("login-cli");
        Files.writeString(command, "#!/bin/sh\n" + body);
        assertThat(command.toFile().setExecutable(true)).isTrue();
        when(launch.command(any())).thenReturn(command.toString());
        when(connection.check(any())).thenReturn(state("LOGIN_REQUIRED"));
        return new ProviderLoginAdapter(launch, connection, timeout);
    }
    ProviderConnection.Status state(String state) { return new ProviderConnection.Status("CODEX", state, "now", "NOT_CHECKED"); }
    String await(ProviderLoginAdapter adapter, String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (adapter.status(id).state().equals("PENDING") && System.nanoTime() < deadline) Thread.sleep(20);
        return adapter.status(id).state();
    }

    @Test
    void requiresConsentAndDoesNotRelaunchAnAuthenticatedAccount() throws Exception {
        var adapter = fixture("touch '" + directory.resolve("marker") + "'", Duration.ofSeconds(3));
        try {
            assertThatThrownBy(() -> adapter.start(Provider.CODEX, false)).isInstanceOf(LibraryException.class);
            verifyNoInteractions(connection);
            when(connection.check(Provider.CODEX)).thenReturn(state("AUTHENTICATED"));
            assertThat(adapter.start(Provider.CODEX, true).state()).isEqualTo("AUTHENTICATED");
            assertThat(directory.resolve("marker")).doesNotExist();
        } finally { adapter.close(); }
    }

    @Test
    void successRequiresAuthenticationRecheckAndNeverReturnsOutput() throws Exception {
        var adapter = fixture("[ \"$*\" = login ] || exit 9\n[ -z \"$(cat)\" ] || exit 8\necho 'secret-token fake-url'\n", Duration.ofSeconds(3));
        try {
            when(connection.check(Provider.CODEX)).thenReturn(state("LOGIN_REQUIRED"), state("AUTHENTICATED"));
            var started = adapter.start(Provider.CODEX, true);
            assertThat(await(adapter, started.id())).isEqualTo("AUTHENTICATED");
            assertThat(adapter.status(started.id()).toString()).doesNotContain("secret-token", "fake-url");
            when(connection.check(Provider.CODEX)).thenReturn(state("LOGIN_REQUIRED"));
            assertThat(await(adapter, adapter.start(Provider.CODEX, true).id())).isEqualTo("FAILED");
        } finally { adapter.close(); }
    }

    @Test
    void cancellationAndShutdownRejectOverlapAndPreserveTerminalState() throws Exception {
        var adapter = fixture("sleep 30", Duration.ofSeconds(3));
        var started = adapter.start(Provider.CODEX, true);
        assertThatThrownBy(() -> adapter.start(Provider.CLAUDE, true)).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> adapter.cancel("another-request")).isInstanceOf(LibraryException.class);
        assertThat(adapter.cancel(started.id()).state()).isEqualTo("CANCELLED");
        adapter.close();
        Thread.sleep(100);
        assertThat(adapter.status(started.id()).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> adapter.start(Provider.CODEX, true)).isInstanceOf(LibraryException.class);
    }

    @Test
    void timeLimitAndUnsupportedToolsNeverClaimLoginSuccess() throws Exception {
        var adapter = fixture("sleep 30", Duration.ofMillis(100));
        try {
            assertThat(await(adapter, adapter.start(Provider.CODEX, true).id())).isEqualTo("TIMED_OUT");
            when(connection.check(any())).thenReturn(state("UNSUPPORTED"));
            assertThatThrownBy(() -> adapter.start(Provider.CLAUDE, true)).isInstanceOf(LibraryException.class);
        } finally { adapter.close(); }
    }
}
