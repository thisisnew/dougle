package dev.dougle.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static dev.dougle.investigation.domain.ToolExecution.Status.*;

import dev.dougle.investigation.application.LocalSearchQuery;
import dev.dougle.investigation.domain.ToolExecution;
import dev.dougle.library.application.LibraryService;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.search.application.SearchService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"forge.desktop=false", "forge.search-model-directory="})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalWorkspaceSearchIntegrationTest {
    private static final Path DATA;
    private static final byte[] CONTENT = ("Strategy delegates to a fictional local policy.\n"
            + "Strategy evidence remains read only. ".repeat(30)).getBytes(StandardCharsets.UTF_8);
    private static final ToolExecution.Budget BUDGET = new ToolExecution.Budget(6, Duration.ofSeconds(15),
            Duration.ofSeconds(5), 12000, 6000);

    static {
        try { DATA = Files.createTempDirectory("forge-local-search-tool-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }

    @Autowired LocalToolExecutorFactory factory;
    @Autowired LibraryService library;
    @Autowired LibraryStore store;
    @Autowired SearchService search;
    @Autowired JdbcTemplate jdbc;
    private String workspace;
    private String otherWorkspace;
    private String entry;
    private String otherEntry;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("tool fixture").id();
        entry = library.upload(workspace, null, "Strategy.txt", CONTENT).id();
        otherWorkspace = library.createWorkspace("other tool fixture").id();
        otherEntry = library.upload(otherWorkspace, null, "PrivateStrategy.txt",
                "Strategy otherworkspaceprivatevalue".getBytes(StandardCharsets.UTF_8)).id();
        search.refresh();
    }

    @Test
    void springFactoryExecutesActualLocalWorkspaceSearchWithoutMutations() {
        var before = store.entry(workspace, entry).orElseThrow();
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation(workspace, "Strategy"));
            assertEquals(OK, result.status());
            assertFalse(result.truncated());
            assertTrue(result.output().contains(entry));
            assertTrue(result.output().contains("entries/" + entry));
            assertTrue(result.output().contains("\"pathKind\":\"workspace-entry\""));
            assertFalse(result.output().contains("Strategy.txt"));
            assertTrue(result.output().contains("fictional local policy"));
            assertTrue(result.output().contains("\"startLine\":"));
            assertTrue(result.output().contains("\"endLine\":"));
            assertFalse(result.output().contains(otherEntry));
            assertFalse(result.output().contains("otherworkspaceprivatevalue"));
            assertEquals(otherWorkspace, store.activeWorkspace());
            assertEquals(before, store.entry(workspace, entry).orElseThrow());
            assertArrayEquals(CONTENT, library.content(workspace, entry));
            for (String table : List.of("source", "knowledge_card", "evidence_bundle", "answer_approval", "answer_job")) {
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            }
        }
    }

    @Test
    void differentWorkspaceRequiresItsOwnFactorySession() {
        try (var executor = factory.open(otherWorkspace, BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation(otherWorkspace, "Strategy"));
            assertEquals(OK, result.status());
            assertTrue(result.output().contains(otherEntry));
            assertFalse(result.output().contains(entry));
        }
    }

    @Test
    void remoteEnvironmentOtherWorkspaceAndBroaderScopeAreDenied() {
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            for (var binding : List.of(LocalWorkspaceSearchTool.binding(otherWorkspace),
                    new ToolExecution.Binding(workspace, "remote", "workspace-search"),
                    new ToolExecution.Binding(workspace, "local", "*"))) {
                var result = executor.execute(new ToolExecution.Invocation<>(LocalWorkspaceSearchTool.ID,
                        binding, new LocalSearchQuery("Strategy")));
                assertEquals(DENIED, result.status());
                assertEquals("", result.output());
            }
        }
    }

    @Test
    void unknownUrlAndShellToolNamesCannotExecute() {
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            for (String id : List.of("shell", "https://example.invalid", "curl")) {
                assertEquals(DENIED, executor.execute(new ToolExecution.Invocation<>(id,
                        LocalWorkspaceSearchTool.binding(workspace), new LocalSearchQuery("Strategy"))).status());
            }
        }
    }

    @Test
    void missingWorkspaceCannotOpenSession() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.open("missing", BUDGET, new ToolExecution.Cancellation()));
    }

    @Test
    void deletingWorkspaceInvalidatesAlreadyOpenSession() {
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            assertEquals(OK, executor.execute(invocation(workspace, "Strategy")).status());
            library.deleteWorkspace(workspace);
            var result = executor.execute(invocation(workspace, "policy"));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void noMatchingEvidenceReturnsEmpty() {
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation(workspace, "zqxvnonexistentfixture"));
            assertEquals(EMPTY, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void whitespaceEquivalentQuestionsAreDuplicateInvocations() {
        try (var executor = factory.open(workspace, BUDGET, new ToolExecution.Cancellation())) {
            assertEquals(OK, executor.execute(invocation(workspace, "Strategy")).status());
            var result = executor.execute(invocation(workspace, " Strategy "));
            assertEquals(DENIED, result.status());
            assertEquals("duplicate_invocation", result.reason());
        }
    }

    @Test
    void evidenceOutputUsesExecutorBudgetAndDisclosesTruncation() {
        var budget = new ToolExecution.Budget(3, Duration.ofSeconds(15), Duration.ofSeconds(5), 300, 300);
        try (var executor = factory.open(workspace, budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation(workspace, "Strategy"));
            assertEquals(OK, result.status());
            assertEquals(300, result.output().length());
            assertTrue(result.truncated());
            assertTrue(result.output().contains(entry));
            assertEquals("output_budget", executor.execute(invocation(workspace, "policy")).reason());
        }
    }

    @Test
    void cancellationReturnsNoEvidence() {
        var cancellation = new ToolExecution.Cancellation();
        try (var executor = factory.open(workspace, BUDGET, cancellation)) {
            cancellation.cancel();
            var result = executor.execute(invocation(workspace, "Strategy"));
            assertEquals(CANCELLED, result.status());
            assertEquals("", result.output());
        }
    }

    private static ToolExecution.Invocation<LocalSearchQuery> invocation(String workspace, String question) {
        return new ToolExecution.Invocation<>(LocalWorkspaceSearchTool.ID,
                LocalWorkspaceSearchTool.binding(workspace), new LocalSearchQuery(question));
    }
}
