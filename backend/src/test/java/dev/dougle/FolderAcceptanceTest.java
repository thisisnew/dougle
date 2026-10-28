package dev.dougle;

import dev.dougle.ingestion.application.ProcessingProgress;
import dev.dougle.sources.adapter.LocalFolderCollector;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.collection", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FolderAcceptanceTest {
    @TempDir Path directory;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final LocalFolderCollector collector = new LocalFolderCollector();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/folder-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 10; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(226 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int fixture, int action) throws Exception {
        Path root = Files.createDirectories(directory.resolve(id));
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_FOLDER_COLLECTOR_OWNED_SYNTHETIC_FILES");
        result.put("externalRequests", 0);
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("Native folder picker/UI", "SourceService persistence and search", "UI error display"));
        var manifest = new TreeMap<String, String>();
        long started = System.nanoTime();
        try {
            int count = fixture < 5 ? new int[]{1, 100, 50, 501, 5000}[fixture] : 1;
            for (int number = 0; number < count; number++) {
                String path = fixture == 1 ? "level/".repeat(10) + number + ".txt"
                        : fixture == 2 ? "package" + number + "/Main.java" : "note" + number + ".txt";
                String content = fixture == 2 ? "package p" + number + "; class Main { int value = " + number + "; }"
                        : "BLUE72 synthetic fixture " + number;
                write(root, manifest, path, content);
            }
            if (fixture == 5) {
                for (String excluded : List.of(".git", "node_modules")) {
                    Files.createDirectories(root.resolve(excluded));
                    Files.writeString(root.resolve(excluded).resolve("excluded.txt"), "EXCLUDED_ONLY_MARKER");
                }
                write(root, manifest, "build/generated.txt", "Build directory is not in the configured exclusion policy");
                result.put("buildPolicy", "build is currently collected; .git and node_modules excluded");
            }
            if (fixture == 6) {
                Path outside = directory.resolve(id + "-outside.txt");
                Files.writeString(outside, "OUTSIDE_SECRET_MARKER");
                Files.createSymbolicLink(root.resolve("outside.txt"), outside);
            }
            if (fixture == 7) Files.createSymbolicLink(root.resolve("cycle"), root);
            if (fixture == 8) {
                Files.writeString(root.resolve(".env"), "SYNTHETIC_SECRET_MARKER");
                Files.createDirectories(root.resolve(".ssh"));
                Files.writeString(root.resolve(".ssh/id_rsa"), "SYNTHETIC_SECRET_MARKER");
                write(root, manifest, ".notes.txt", "Ordinary hidden note BLUE72");
            }
            if (fixture == 9) Files.setPosixFilePermissions(root.resolve("note0.txt"), PosixFilePermissions.fromString("r--r--r--"));
            result.put("expectedManifest", manifest);
            var before = fingerprint(root);
            if (fixture == 6 || fixture == 7) {
                try (var snapshot = collector.collect(root.toString())) {
                    assertThat(snapshot.files()).noneSatisfy(file -> assertThat(file.path()).isIn("outside.txt", "cycle"));
                } catch (dev.dougle.library.application.LibraryException failure) {
                    result.put("safeRejection", failure.getMessage());
                    assertThat(failure.getMessage()).contains("심볼릭");
                }
                assertThat(fingerprint(root)).isEqualTo(before);
                result.put("status", "PARTIAL");
                result.put("checkStatus", "PASS");
                result.put("remaining", List.of("Collector rejects entire folder rather than collecting healthy siblings", "Action " + action + " cannot reach successful baseline; retry/UI/search not executed"));
                return;
            }
            collect(root, manifest, result);
            assertThat(fingerprint(root)).as("Collection must not modify original bytes, mtime or permissions").isEqualTo(before);
            if (action == 1) {
                write(root, manifest, "new.txt", "AMBER24 newly added synthetic evidence");
                collect(root, manifest, result);
            } else if (action == 2) {
                String original = manifest.firstKey();
                Files.delete(root.resolve(original));
                manifest.remove(original);
                write(root, manifest, "moved.txt", "BLUE36 modified synthetic evidence");
                collect(root, manifest, result);
            } else if (action == 3) {
                try (var observer = ProcessingProgress.observe(step -> {
                    if (step.stage().equals("READING_BYTES")) throw new CancellationException("Owned fixture cancellation");
                })) {
                    assertThatThrownBy(() -> {
                        try (var snapshot = collector.collect(root.toString())) {
                            fail("Cancellation must not publish a completed snapshot");
                        }
                    }).isInstanceOf(CancellationException.class);
                }
                collect(root, manifest, result);
            } else if (action == 4) {
                Path moved = directory.resolve(id + "-unavailable");
                Files.move(root, moved);
                try {
                    assertThatThrownBy(() -> collector.collect(root.toString()))
                            .isInstanceOf(dev.dougle.library.application.LibraryException.class);
                } finally { Files.move(moved, root); }
                collect(root, manifest, result);
            }
            result.put("status", "PARTIAL");
            result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.getMessage());
            throw failure;
        } finally {
            result.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            results.add(result);
            System.out.println("FOLDER_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }

    private void write(Path root, Map<String, String> manifest, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        manifest.put(relative, dev.dougle.sources.adapter.CollectionLimits.hash(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void collect(Path root, Map<String, String> manifest, Map<String, Object> result) throws Exception {
        List<CollectedSnapshot.File> captured;
        try (var snapshot = collector.collect(root.toString())) {
            var actual = new TreeMap<String, String>();
            for (var file : snapshot.files()) actual.put(file.path(), file.hash());
            assertThat(actual).isEqualTo(manifest);
            assertThat(snapshot.failures()).isEmpty();
            result.put("actualCount", actual.size());
            result.put("revision", snapshot.revision());
            captured = snapshot.files();
        }
        for (var file : captured) assertThatThrownBy(file::open).isInstanceOf(java.io.IOException.class);
    }

    private Map<String, String> fingerprint(Path root) throws Exception {
        var values = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                values.put(root.relativize(path).toString(), dev.dougle.sources.adapter.CollectionLimits.hash(Files.readAllBytes(path))
                        + ":" + Files.getLastModifiedTime(path) + ":" + Files.getPosixFilePermissions(path));
            }
        }
        return values;
    }
}
