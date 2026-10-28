package dev.dougle;

import dev.dougle.conversation.application.AnswerJobStore;
import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.EvidenceBundleService;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.application.SourceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "forge.acceptance.workflow", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibraryWorkflowAcceptanceTest {
    private static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-library-acceptance-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @Autowired EvidenceBundleService bundles;
    @Autowired ChatThreadService threads;
    @Autowired AnswerJobStore jobs;
    @Autowired SourceService sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    private final List<Map<String, Object>> results = new ArrayList<>();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/library-workflow-acceptance-results.json"), new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int base : List.of(201, 801, 826)) {
            for (int fixture = 0; fixture < 5; fixture++) {
                for (int action = 0; action < 5; action++) {
                    int selectedFixture = fixture;
                    int selectedAction = action;
                    String id = "T%04d".formatted(base + fixture * 5 + action);
                    tests.add(DynamicTest.dynamicTest(id, () -> execute(id, base, selectedFixture, selectedAction)));
                }
            }
        }
        return tests.stream();
    }

    private void execute(String id, int base, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_SPRING_SERVICES_SQLITE_AND_LEXICAL_SEARCH");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("Native UI, selection layout, cancellation dialog and delayed HTTP responses", "Actual process restart", "Real AI answer quality"));
        long start = System.nanoTime();
        try {
            jdbc.update("DELETE FROM workspace");
            search.refresh();
            if (base == 201) files(fixture, action, result);
            else if (base == 801) history(fixture, action, result);
            else workspaces(fixture, action, result);
            result.put("status", "PARTIAL");
            result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.getMessage());
            throw failure;
        } finally {
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("LIBRARY_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private void files(int fixture, int action, Map<String, Object> result) throws Exception {
        String workspace = library.createWorkspace("File acceptance").id();
        String other = library.createWorkspace("Other workspace").id();
        String first = library.upload(workspace, null, "policy.txt", bytes("BLUE72 original synthetic policy")).id();
        String otherEntry = library.upload(other, null, "policy.txt", bytes("BLUE72 other workspace policy")).id();
        if (fixture == 1) {
            assertThat(uploadStatus(workspace, "policy.txt", "BLUE36 conflict")).isEqualTo(409);
            assertThat(library.content(workspace, first)).isEqualTo(bytes("BLUE72 original synthetic policy"));
        } else if (fixture == 2) library.upload(workspace, null, "copy.txt", bytes("BLUE72 original synthetic policy"));
        else if (fixture == 3) {
            Path root = Files.createDirectories(DATA.resolve("fixture-" + UUID.randomUUID()));
            Files.writeString(root.resolve("policy.txt"), "BLUE72 original synthetic policy");
            var source = sources.create(workspace, "Folder copy", "LOCAL_FOLDER", root.toString(), "");
            assertThat(sources.sync(workspace, source.id()).status()).isEqualTo("READY");
        }
        search.refresh();
        var fixed = bundles.create(workspace, "BLUE72", List.of(), false);
        assertThat(fixed.payload().items()).isNotEmpty();
        if (action == 0) {
            assertThat(uploadStatus(workspace, "policy.txt", "BLUE72 original synthetic policy")).isEqualTo(409);
        } else if (action == 1) {
            assertThat(uploadStatus(workspace, "policy.txt", "BLUE36 replacement")).isEqualTo(409);
            result.put("replacementPolicy", "Explicit conflict; existing file not silently overwritten. Delete and upload are separate operations.");
            library.deleteEntry(workspace, first);
            var replacement = library.upload(workspace, null, "policy.txt", bytes("BLUE36 replacement"));
            search.refresh();
            assertThat(search.search(workspace, "BLUE36", List.of())).anySatisfy(hit -> assertThat(hit.entryId()).isEqualTo(replacement.id()));
        } else if (action == 2) {
            var renamed = library.upload(workspace, null, "renamed.txt", bytes("BLUE72 original synthetic policy"));
            assertThat(renamed.name()).isEqualTo("renamed.txt");
            assertThat(library.content(workspace, renamed.id())).isEqualTo(library.content(workspace, first));
        } else if (action == 3) {
            library.deleteEntry(workspace, first);
            search.refresh();
            assertThat(search.search(workspace, "BLUE72", List.of())).noneSatisfy(hit -> assertThat(hit.entryId()).isEqualTo(first));
        } else {
            try (var executor = Executors.newFixedThreadPool(2)) {
                var tasks = List.<java.util.concurrent.Callable<Integer>>of(() -> uploadConflict(workspace), () -> uploadConflict(workspace));
                var codes = executor.invokeAll(tasks).stream().map(future -> {
                    try { return future.get(); } catch (Exception failure) { throw new RuntimeException(failure); }
                }).toList();
                assertThat(codes).containsExactlyInAnyOrder(201, 409);
            }
        }
        assertThat(bundles.get(workspace, fixed.id())).isEqualTo(fixed);
        assertThat(library.content(other, otherEntry)).isEqualTo(bytes("BLUE72 other workspace policy"));
        assertThat(search.search(other, "BLUE72", List.of())).allSatisfy(hit -> assertThat(hit.entryId()).isEqualTo(otherEntry));
        result.put("fixedEvidencePreserved", true);
    }

    private int uploadConflict(String workspace) throws Exception {
        return uploadStatus(workspace, "concurrent.txt", "BLUE72 concurrent fixture");
    }

    private int uploadStatus(String workspace, String name, String content) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", name, "text/plain", bytes(content));
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/workspaces/" + workspace + "/files").file(file).header("X-Forge-Client", "1"))
                .andReturn().getResponse().getStatus();
    }

    private void history(int fixture, int action, Map<String, Object> result) {
        String workspace = library.createWorkspace("History acceptance").id();
        library.upload(workspace, null, "policy.txt", bytes("BLUE72 AMBER24 GREEN12 synthetic policy"));
        search.refresh();
        var ids = new ArrayList<String>();
        for (int thread = 0; thread < 3; thread++) {
            String title = fixture == 2 ? "BLUE72 같은 제목" : fixture == 3 ? "BLUE72 " + "긴 한글 제목 ".repeat(20) + "\n" + thread : "BLUE72 thread " + thread;
            var current = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), null, null, title, List.of()));
            String threadId = current.payload().chatContext().threadId();
            ids.add(threadId);
            for (int turn = 1; turn < (fixture == 1 ? 20 : 1); turn++) {
                current = threads.create(workspace, new ChatThreadService.Create(UUID.randomUUID().toString(), threadId, current.id(), "BLUE72 turn " + turn, List.of()));
            }
            if (fixture == 4) {
                String status = List.of("SUCCEEDED", "FAILED", "CANCELLED").get(thread);
                String now = Instant.now().toString();
                jobs.create(new AnswerJob("job-" + thread, workspace, current.id(), "approval-" + thread, "CODEX", "synthetic", "digest", status,
                        status.equals("SUCCEEDED") ? "Synthetic completed answer" : null, null, now, now));
            }
        }
        assertThat(threads.list(workspace)).hasSize(3);
        for (String id : ids) assertThat(threads.get(workspace, id).bundles()).hasSize(fixture == 1 ? 20 : 1);
        if (action == 2) {
            threads.delete(workspace, ids.get(0));
            threads.delete(workspace, ids.get(1));
            assertThat(threads.list(workspace)).extracting(ChatThreadService.Summary::id).containsExactly(ids.get(2));
        } else if (action == 3) {
            var matching = threads.list(workspace).stream().filter(item -> item.question().contains(fixture == 2 ? "같은 제목" : "0")).map(ChatThreadService.Summary::id).toList();
            for (String id : matching) threads.delete(workspace, id);
            assertThat(threads.list(workspace)).hasSize(3 - matching.size());
            result.put("selectionScope", "Service calls on explicit filtered IDs, not UI checkbox selection");
        } else {
            for (String id : ids) assertThat(threads.get(workspace, id).id()).isEqualTo(id);
            assertThat(threads.list(workspace)).hasSize(3);
        }
        assertThat(library.entries(workspace, "ORIGINAL", null)).hasSize(1);
        result.put("turnsPerThread", fixture == 1 ? 20 : 1);
    }

    private void workspaces(int fixture, int action, Map<String, Object> result) {
        String first = library.createWorkspace(fixture == 3 ? "가".repeat(20) : "Workspace A").id();
        String second = library.createWorkspace("Workspace B").id();
        var entry = library.upload(first, null, "policy.txt", bytes("BLUE72 workspace A evidence"));
        if (fixture != 1) library.upload(second, null, "policy.txt", bytes("BLUE36 workspace B evidence"));
        if (fixture == 4) {
            assertThatThrownBy(() -> library.createWorkspace("가".repeat(21))).isInstanceOfSatisfying(LibraryException.class,
                    failure -> assertThat(failure.status()).isEqualTo(400));
            assertThat(library.session().workspaces()).hasSize(2);
        }
        search.refresh();
        var firstTurn = threads.create(first, new ChatThreadService.Create("first", null, null, "BLUE72", List.of()));
        if (fixture == 2) {
            sources.create(first, "Same source", "URL", "https://example.org/a", "");
            sources.create(second, "Same source", "URL", "https://example.org/b", "");
            assertThat(sources.list(first).getFirst().id()).isNotEqualTo(sources.list(second).getFirst().id());
        }
        library.activate(first);
        library.activate(second);
        if (action == 1) { library.activate(first); assertThat(library.session().activeWorkspaceId()).isEqualTo(first); }
        else assertThat(library.session().activeWorkspaceId()).isEqualTo(second);
        assertThat(search.search(second, "BLUE72", List.of())).noneSatisfy(hit -> assertThat(hit.entryId()).isEqualTo(entry.id()));
        assertThat(threads.list(second)).isEmpty();
        assertThatThrownBy(() -> threads.get(second, firstTurn.payload().chatContext().threadId())).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> library.content(second, entry.id())).isInstanceOf(LibraryException.class);
        assertThat(library.content(first, entry.id())).isEqualTo(bytes("BLUE72 workspace A evidence"));
        result.put("crossWorkspaceReadDenied", true);
    }
}
