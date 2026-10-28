package dev.dougle.knowledge.adapter;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompanyExecutableTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"claude-work", "codex-team", "another-company-ai", "ai_123", "ai.v2"})
    void resolvesPerUserNamesWithoutExecutingThem(String name) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, "#!/bin/sh\nexit 98\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        assertEquals(file.toString(), CompanyExecutable.resolve(name, List.of(root)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../claude", "./claude", "claude --dangerously-skip-permissions", "$(touch marker)", "`whoami`", "claude;id", "claude\nwork", "alias claude=work", "~/bin/claude", "--help", "", "missing-command"})
    void refusesShellExpansionAndMissingCommands(String value) {
        if (value.isEmpty()) assertEquals("", CompanyExecutable.resolve(value, List.of(root)));
        else assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve(value, List.of(root)));
    }

    @Test void requiresExecutableFileAndIgnoresRelativeSearchDirectories() throws Exception {
        Path file = Files.writeString(root.resolve("claude-work"), "never execute");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve(file.toString(), List.of()));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        assertEquals(file.toString(), CompanyExecutable.resolve(file.toString(), List.of()));
        assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve("claude-work", List.of(Path.of("."))));
    }

    @Test void supportsUnicodeSpacesAndExecutableSymlinksWithoutShellParsing() throws Exception {
        Path directory = Files.createDirectories(root.resolve("회사 도구"));
        Path executable = Files.writeString(directory.resolve("custom ai"), "#!/bin/sh\nexit 98\n");
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        Path link = Files.createSymbolicLink(root.resolve("claude-work"), executable);
        assertEquals(executable.toString(), CompanyExecutable.resolve(executable.toString(), List.of()));
        assertEquals(link.toString(), CompanyExecutable.resolve("claude-work", List.of(root)));
        assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve(directory.toString(), List.of()));
        Files.delete(executable);
        assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve("claude-work", List.of(root)));
    }

    @Test void searchOrderIsStableAndDoesNotFallBackToAnotherUsersCommand() throws Exception {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        for (Path directory : List.of(first, second)) {
            Path file = Files.writeString(directory.resolve("codex-team"), "#!/bin/sh\nexit 98\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        }
        assertEquals(first.resolve("codex-team").toString(), CompanyExecutable.resolve("codex-team", List.of(first, second)));
        assertThrows(RuntimeException.class, () -> CompanyExecutable.resolve("codex-team", List.of(root.resolve("other-user"))));
    }
}
