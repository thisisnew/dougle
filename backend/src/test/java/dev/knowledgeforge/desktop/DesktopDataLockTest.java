package dev.knowledgeforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopDataLockTest {
    @TempDir Path temporary;

    @Test
    void privateDirectoryRejectsDuplicateAndReleasesOnShutdown() throws Exception {
        Path directory = temporary.resolve("data");
        try (DesktopDataLock first = DesktopDataLock.acquire(directory.toString())) {
            assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(directory.resolve(".desktop.lock")))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThatThrownBy(() -> DesktopDataLock.acquire(directory.toString())).isInstanceOf(IllegalStateException.class);
        }
        try (DesktopDataLock restarted = DesktopDataLock.acquire(directory.toString())) {
            assertThat(restarted).isNotNull();
        }
    }

    @Test
    void rejectsMissingRelativeAndSymlinkPaths() throws Exception {
        assertThatThrownBy(() -> DesktopDataLock.acquire(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DesktopDataLock.acquire("relative")).isInstanceOf(IllegalStateException.class);
        Path link = temporary.resolve("link");
        Files.createSymbolicLink(link, temporary);
        assertThatThrownBy(() -> DesktopDataLock.acquire(link.toString())).isInstanceOf(IllegalStateException.class);
        Files.createSymbolicLink(temporary.resolve(".desktop.lock"), temporary.resolve("target"));
        assertThatThrownBy(() -> DesktopDataLock.acquire(temporary.toString())).isInstanceOf(java.io.IOException.class);
        assertThat(temporary.resolve("target")).doesNotExist();
    }
}
