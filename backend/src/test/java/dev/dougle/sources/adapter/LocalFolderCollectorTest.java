package dev.dougle.sources.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalFolderCollectorTest {
    @TempDir Path directory;
    private final LocalFolderCollector collector = new LocalFolderCollector();

    @Test void unhealthyEntriesDoNotDiscardHealthySourceCode() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Files.writeString(root.resolve("Main.java"), "class Main { String marker = \"HEALTHY\"; }");
        Path outside = Files.writeString(directory.resolve("secret.txt"), "OUTSIDE_SECRET");
        Files.createSymbolicLink(root.resolve("external"), outside);
        Files.createSymbolicLink(root.resolve("cycle"), root);
        Files.writeString(root.resolve("x".repeat(121)), "LONG_NAME");
        Files.writeString(root.resolve("bad\\name.txt"), "INVALID_NAME");
        Path deep = Files.createDirectories(root.resolve("level/".repeat(17)));
        Files.writeString(deep.resolve("deep.txt"), "DEEP_CONTENT");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/ignored.txt"), "DEPENDENCY");
        try (var snapshot = collector.collect(root.toString())) {
            assertThat(snapshot.files()).extracting(file -> file.path()).containsExactly("Main.java");
            assertThat(snapshot.failures()).hasSize(5).containsKeys("external", "cycle", "bad\\name.txt");
            assertThat(new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8)).contains("HEALTHY");
        }
        assertThat(Files.readString(outside)).isEqualTo("OUTSIDE_SECRET");
        assertThat(Files.isSymbolicLink(root.resolve("cycle"))).isTrue();
    }

    @Test void missingRootExplainsLocalAccessRatherThanNetwork() {
        assertThatThrownBy(() -> collector.collect(directory.resolve("missing").toString()))
                .hasMessageContaining("폴더 위치").hasMessageContaining("macOS").hasMessageNotContaining("인터넷");
    }

    @Test void rootSymlinkIsStillRejected() throws Exception {
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), directory);
        assertThatThrownBy(() -> collector.collect(alias.toString())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }
}
