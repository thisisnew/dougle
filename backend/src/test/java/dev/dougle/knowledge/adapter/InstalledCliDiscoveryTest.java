package dev.dougle.knowledge.adapter;

import static org.junit.jupiter.api.Assertions.*;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstalledCliDiscoveryTest {
    @TempDir Path home;

    private Path executable(String relative) throws Exception {
        Path path = home.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\ntouch '" + home.resolve("must-not-run") + "'\n");
        assertTrue(path.toFile().setExecutable(true, true));
        return path;
    }

    @ParameterizedTest
    @ValueSource(strings = {".local/bin/claude-work", "bin/work-claude", ".npm-global/bin/claude-team", ".volta/bin/claude",
            ".asdf/shims/claude", ".nvm/versions/node/v22/bin/claude", ".local/share/fnm/node-versions/v22/installation/bin/claude",
            ".asdf/installs/nodejs/22/bin/claude", "Library/Application Support/fnm/node-versions/v22/installation/bin/claude",
            "Library/pnpm/claude", ".local/share/mise/installs/node/22/bin/claude", ".bun/bin/claude"})
    void discoversWithoutTerminalPathOrExecutingAnything(String relative) throws Exception {
        Path path = executable(relative);
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertEquals(path.toString(), discovery.defaultCommand(Provider.CLAUDE, "claude"));
        assertEquals(1, discovery.find(Provider.CLAUDE).size());
        assertFalse(Files.exists(home.resolve("must-not-run")));
        assertTrue(discovery.find(Provider.CODEX).isEmpty());
    }

    @Test void discoversCodexButPreservesExplicitAndBundledCommands() throws Exception {
        Path path = executable("bin/codex-work");
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertEquals(path.toString(), discovery.defaultCommand(Provider.CODEX, "codex"));
        assertEquals("/bundled/codex", discovery.defaultCommand(Provider.CODEX, "/bundled/codex"));
        assertEquals("custom-launcher", discovery.defaultCommand(Provider.CODEX, "custom-launcher"));
    }

    @Test void multipleCompanyToolsRequireSelectionInsteadOfGuessing() throws Exception {
        executable("bin/claude-work"); executable("bin/claude-personal");
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertEquals(2, discovery.find(Provider.CLAUDE).size());
        assertEquals("claude", discovery.defaultCommand(Provider.CLAUDE, "claude"));
    }

    @Test void skipsNonExecutableDirectoriesBrokenLinksAndDeduplicatesSymlinks() throws Exception {
        Path original = executable("bin/claude-work");
        Files.createSymbolicLink(home.resolve("bin/claude-team"), original);
        Files.createSymbolicLink(home.resolve("bin/claude-broken"), home.resolve("absent"));
        Files.createDirectory(home.resolve("bin/claude-folder"));
        Files.writeString(home.resolve("bin/claude-text"), "not executable");
        var discovery = new InstalledCliDiscovery(home, List.of(home.resolve("bin"), Path.of(".")));
        assertEquals(1, discovery.find(Provider.CLAUDE).size());
        assertFalse(Files.exists(home.resolve("must-not-run")));
    }

    @Test void respectsProviderAndStandardNameWithoutScanningProjects() throws Exception {
        executable("project/claude-work"); executable("bin/notclaude"); executable("bin/codex-team");
        Path standard = executable("bin/claude"); executable("bin/claude-work");
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertEquals(standard.toString(), discovery.defaultCommand(Provider.CLAUDE, "claude"));
        assertEquals(2, discovery.find(Provider.CLAUDE).size());
    }

    @Test void findsSimpleAliasesAndForwardingFunctionsWithoutRunningProfiles() throws Exception {
        Path binary = executable("회사 도구/claude");
        Files.writeString(home.resolve(".zshrc"), "touch '" + home.resolve("profile-must-not-run") + "'\n"
                + "alias work-ai='\"" + binary + "\"'\n"
                + "claude-work() { \"" + binary + "\" \"$@\"; }\n");
        var discovery = new InstalledCliDiscovery(home, List.of());
        assertEquals(binary.toString(), discovery.defaultCommand(Provider.CLAUDE, "claude"));
        assertEquals("work-ai", discovery.find(Provider.CLAUDE).getFirst().name());
        assertFalse(Files.exists(home.resolve("profile-must-not-run")));
        assertFalse(Files.exists(home.resolve("must-not-run")));
    }

    @Test void refusesComplexAliasesRatherThanDroppingTheirEnvironmentOrArguments() throws Exception {
        Path binary = executable("hidden/claude");
        Files.writeString(home.resolve(".zshrc"), "alias claude-work='TOKEN=secret " + binary + "'\n"
                + "alias claude-personal='" + binary + " --settings special'\n"
                + "alias claude-other='$(touch marker)'\n"
                + "claude-team() { touch marker; " + binary + " \"$@\"; }\n");
        assertTrue(new InstalledCliDiscovery(home, List.of()).find(Provider.CLAUDE).isEmpty());
    }
}
