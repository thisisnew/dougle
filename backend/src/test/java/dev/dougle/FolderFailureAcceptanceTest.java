package dev.dougle;

import dev.dougle.ingestion.adapter.TikaJavaAnalyzer;
import dev.dougle.ingestion.application.ProcessingProgress;
import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.sources.adapter.LocalFolderCollector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.folderFailure", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FolderFailureAcceptanceTest {
    @TempDir Path directory;
    private final List<Map<String, Object>> results = new ArrayList<>();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/folder-failure-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(276 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_FOLDER_COLLECTOR_ANALYZER_LEXICAL_INDEX_OWNED_FAILURE_FIXTURES");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("SourceService persistence and UI error/hover", "Concurrent clicks, app restart and actual AI answers", "Recovery is repaired fixture collection, not persisted source retry"));
        long start = System.nanoTime();
        Path root = Files.createDirectories(directory.resolve(id));
        Path bad = root.resolve(fixture == 2 ? "broken.pdf" : "unavailable.txt");
        var collector = new LocalFolderCollector();
        try {
            int healthyCount = fixture == 4 ? 0 : fixture == 2 ? 1 : 9;
            for (int number = 0; number < healthyCount; number++) Files.writeString(root.resolve("healthy" + number + ".txt"), "BLUE72 independent healthy evidence " + number);
            Files.writeString(bad, "BLUE36 previous content");
            if (action == 1) {
                try (var baseline = collector.collect(root.toString())) {
                    result.put("baselineRevision", baseline.revision());
                    assertThat(baseline.files()).hasSize(healthyCount + 1);
                }
            }
            if (fixture == 0 || fixture == 4) {
                Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("---------"));
                assertThat(Files.isReadable(bad)).as("Permission fixture must actually deny reads on this host").isFalse();
            } else if (fixture == 1) {
                try (var file = new java.io.RandomAccessFile(bad.toFile(), "rw")) { file.setLength(256L * 1024 * 1024 + 1); }
            } else if (fixture == 2) Files.writeString(bad, "%PDF-1.7\nsynthetic truncated invalid PDF");
            int attempts = action == 2 ? 3 : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                if (fixture == 3) Files.writeString(bad, "Will disappear during traversal");
                try (var observation = ProcessingProgress.observe(step -> {
                    if (fixture == 3 && step.stage().equals("READING") && step.file().equals("unavailable.txt")) {
                        try { Files.delete(bad); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    }
                }); var snapshot = collector.collect(root.toString());
                     var index = new LuceneSearchIndex(directory.resolve(id + "-index-" + attempt).toString())) {
                    int ready = 0;
                    int analysisFailures = 0;
                    for (var file : snapshot.files()) {
                        try (var input = file.open()) {
                            var analysis = new TikaJavaAnalyzer().analyzeStream(file.path(), input);
                            if (analysis.status().equals("READY")) {
                                ready++;
                                index.put(new SearchIndex.Document(file.path(), "one", file.path(), analysis.text(), "", "local", "", file.path()));
                            } else analysisFailures++;
                        }
                    }
                    index.commit();
                    assertThat(ready).isEqualTo(healthyCount);
                    if (fixture == 2) assertThat(analysisFailures).isEqualTo(1);
                    else assertThat(snapshot.failures()).containsKey("unavailable.txt");
                    if (healthyCount > 0) assertThat(index.search("one", "BLUE72", List.of())).isNotEmpty();
                    else assertThat(index.search("one", "BLUE72", List.of())).isEmpty();
                    result.put("readyFiles", ready);
                    result.put("collectionFailures", snapshot.failures());
                    result.put("analysisFailures", analysisFailures);
                }
            }
            if (action == 3) {
                if (Files.exists(bad)) Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("rw-------"));
                Files.deleteIfExists(bad);
                Files.writeString(root.resolve("repaired.txt"), "AMBER24 repaired evidence");
                try (var recovered = collector.collect(root.toString())) {
                    assertThat(recovered.failures()).isEmpty();
                    assertThat(recovered.files()).hasSize(healthyCount + 1);
                }
            }
            result.put("status", "PARTIAL");
            result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.toString());
            throw failure;
        } finally {
            if (Files.exists(bad)) Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("rw-------"));
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("FOLDER_FAILURE_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }
}
