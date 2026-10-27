package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.application.SearchService;
import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.application.SourceService;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.sources.domain.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@EnabledIfSystemProperty(named = "forge.acceptance.syncProgress", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncProgressAcceptanceTest {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-sync-acceptance-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
    }
    @Autowired LibraryService library;
    @Autowired LibraryStore libraryStore;
    @Autowired SourceService sources;
    @Autowired SourceStore store;
    @Autowired List<Collector> collectors;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean DocumentAnalyzer analyzer;
    private final List<Map<String, Object>> results = new ArrayList<>();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/sync-progress-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(576 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_SOURCE_SERVICE_SQLITE_COLLECTOR_ANALYZER_WITH_BOUNDED_ANALYZER_LATCH");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("Native UI navigation locks, hover/focus, visible stage order and ETA", "Process shutdown/restart; service reconstruction only", "Large-file inner splitting progress not sampled; analyzer boundary only", "No actual AI or external business connections"));
        long start = System.nanoTime();
        var observed = Collections.synchronizedList(new ArrayList<SourceService.Progress>());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            reset(analyzer);
            jdbc.update("DELETE FROM workspace");
            search.refresh();
            String workspace = library.createWorkspace(id).id();
            Path root = Files.createDirectories(DATA.resolve(id));
            if (fixture != 3) {
                int files = fixture == 1 ? 5 : 1;
                for (int number = 0; number < files; number++) Files.writeString(root.resolve("healthy" + number + ".txt"),
                        fixture == 0 ? "Synthetic background. ".repeat(600_000) + " BLUE72" : "BLUE72 synthetic healthy evidence " + number);
            }
            if (fixture == 2 || fixture == 3) Files.writeString(root.resolve("broken.pdf"), "%PDF-1.7\ntruncated synthetic PDF");
            Source source = sources.create(workspace, "Fixture", "LOCAL_FOLDER", root.toString(), "");
            String previousSuccess = null;
            if (fixture == 4) {
                source = sources.sync(workspace, source.id(), search::refresh);
                assertThat(source.status()).isEqualTo("READY");
                previousSuccess = source.syncedAt();
                Files.writeString(root.resolve("healthy0.txt"), "BLUE36 changed evidence before synthetic analyzer failure");
            }
            String sourceId = source.id();
            doAnswer(invocation -> {
                SourceService.Progress progress = sources.progress(workspace, sourceId);
                observed.add(progress);
                assertThat(progress.stage()).isEqualTo("EXTRACTING");
                assertThat(progress.file()).isEqualTo(invocation.getArgument(0));
                assertThat(progress.cancellable()).isTrue();
                entered.countDown();
                if (action >= 1 && action <= 3 && !release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic test latch timed out");
                if (fixture == 4) throw new java.io.IOException("synthetic analysis outage");
                return invocation.callRealMethod();
            }).when(analyzer).analyzeStream(anyString(), any(java.io.InputStream.class));
            Source completed;
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future = executor.submit(() -> sources.sync(workspace, sourceId, search::refresh));
                try {
                    assertThat(entered.await(20, TimeUnit.SECONDS)).as("Real analysis must begin").isTrue();
                    if (action >= 1 && action <= 3) {
                        assertThat(sources.list(workspace).getFirst().status()).isEqualTo("SYNCING");
                        if (action == 1) {
                            assertThatThrownBy(() -> sources.editMetadata(workspace, sourceId, "Changed", "", 0))
                                    .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(409));
                        } else if (action == 2) sources.cancel(workspace, sourceId);
                        else {
                            assertThatThrownBy(() -> sources.sync(workspace, sourceId)).isInstanceOfSatisfying(LibraryException.class,
                                    failure -> assertThat(failure.status()).isEqualTo(409));
                            sources.poll(workspace, sourceId);
                        }
                    }
                } finally { release.countDown(); }
                completed = future.get(45, TimeUnit.SECONDS);
            }
            if (action == 2) {
                assertThat(completed.status()).isEqualTo("ERROR");
                result.put("cancelledStatus", completed.status());
                doCallRealMethod().when(analyzer).analyzeStream(anyString(), any(java.io.InputStream.class));
                completed = sources.sync(workspace, sourceId, search::refresh);
            }
            result.put("sourceStatus", completed.status());
            result.put("sourceError", completed.error());
            result.put("syncedAt", completed.syncedAt());
            if (fixture == 4 && action != 2) {
                assertThat(completed.status()).isEqualTo("ERROR");
                assertThat(completed.syncedAt()).as("Failure must preserve last successful time").isEqualTo(previousSuccess);
                assertThat(search.search(workspace, "BLUE72", List.of())).isNotEmpty();
            } else if (fixture == 3) {
                assertThat(completed.status()).as("Zero searchable files must not be represented as partial success").isEqualTo("ERROR");
                assertThat(search.search(workspace, "BLUE72", List.of())).isEmpty();
            } else {
                assertThat(completed.status()).isEqualTo(fixture == 2 ? "PARTIAL" : "READY");
                assertThat(search.search(workspace, fixture == 4 ? "BLUE36" : "BLUE72", List.of())).isNotEmpty();
            }
            assertThat(sources.progress(workspace, sourceId).cancellable()).isFalse();
            if (action == 4) {
                var reopened = new SourceService(store, libraryStore, collectors, analyzer);
                assertThat(reopened.list(workspace)).containsExactly(completed);
                assertThat(reopened.progress(workspace, sourceId).stage()).isEqualTo(completed.status());
            }
            result.put("status", "PARTIAL");
            result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.toString());
            throw failure;
        } finally {
            release.countDown();
            result.put("analysisProgress", List.copyOf(observed));
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("SYNC_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }
}
