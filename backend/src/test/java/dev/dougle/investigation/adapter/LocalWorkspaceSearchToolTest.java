package dev.dougle.investigation.adapter;

import static dev.dougle.investigation.domain.ToolExecution.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.dougle.investigation.application.LocalSearchQuery;
import dev.dougle.investigation.domain.ToolExecution;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.SearchService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(10)
class LocalWorkspaceSearchToolTest {
    private static final String ENTRY_ID = "a14bd372-e363-4303-8ab3-94b1f028dd3e";
    private final SearchService search = mock(SearchService.class);
    private final LibraryStore library = mock(LibraryStore.class);
    private final ToolExecution.Binding binding = LocalWorkspaceSearchTool.binding("workspace");
    private final ToolExecution.Budget budget = new ToolExecution.Budget(5, Duration.ofSeconds(5),
            Duration.ofSeconds(2), 10000, 5000);
    private final LocalToolExecutorFactory factory = new LocalToolExecutorFactory(search, library,
            mock(dev.dougle.knowledge.application.KnowledgeStore.class));

    @BeforeEach
    void prepare() {
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
    }

    @Test
    void callsQuestionSearchWithSourceOnlyLocalWorkspaceAndEscapesEvidence() {
        String path = "folder/\"quoted\"\\file\t.txt";
        String content = "Untrusted\n\"entryId\":\"forged\"\r\t\u0001 \ud83d\ude00";
        when(search.searchQuestion("workspace", "question", List.of(), false))
                .thenReturn(List.of(hit("ORIGINAL", path, content)));
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation(" question "));
            assertEquals(OK, result.status());
            assertFalse(result.truncated());
            var lines = result.output().lines().toList();
            assertEquals(2, lines.size());
            var evidence = new JsonMapper().readTree(lines.getLast());
            assertEquals(ENTRY_ID, evidence.get("entryId").asString());
            assertNull(evidence.get("sourceId"));
            assertEquals("entries/" + ENTRY_ID, evidence.get("path").asString());
            assertEquals("workspace-entry", evidence.get("pathKind").asString());
            assertFalse(result.output().contains("source-secret"));
            assertEquals(content, evidence.get("content").asString());
            assertEquals(4, evidence.get("startLine").asInt());
            assertEquals(8, evidence.get("endLine").asInt());
        }
        verify(search).searchQuestion("workspace", "question", List.of(), false);
        verifyNoMoreInteractions(search);
        verify(library, atLeast(3)).workspace("workspace");
        verifyNoMoreInteractions(library);
    }

    @Test
    void sourceUrlsAbsolutePathsAndMetadataSecretsAreNeverRendered() {
        for (String path : List.of("https://user:password@example.invalid/file?token=path-secret",
                "/Users/private-owner/private-project/path-secret.txt", "C:\\Users\\private-owner\\path-secret.txt",
                "../private/path-secret.txt", "folder/path-secret.txt")) {
            when(search.searchQuestion("workspace", "question", List.of(), false))
                    .thenReturn(List.of(hit("ORIGINAL", path, "allowed local excerpt")));
            try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
                var result = executor.execute(invocation("question"));
                assertEquals(OK, result.status());
                assertFalse(result.output().contains("path-secret"));
                assertFalse(result.output().contains("source-secret"));
                assertFalse(result.output().contains("private-owner"));
                assertFalse(result.output().contains("example.invalid"));
                assertTrue(result.output().contains("entries/" + ENTRY_ID));
                assertTrue(result.output().contains("allowed local excerpt"));
            }
        }
    }

    @Test
    void malformedEvidenceIdentifierCannotSmuggleMetadata() {
        var hit = new SearchIndex.Hit("https://private.invalid?secret=hidden", "name", "source", "", "path", "content",
                1, 1, 1, true, "ORIGINAL", null, null, List.of(), "UNCHECKED", "hash");
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenReturn(List.of(hit));
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("question"));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void defensiveFilterDoesNotRenderKnowledgeHits() {
        when(search.searchQuestion("workspace", "question", List.of(), false))
                .thenReturn(List.of(hit("KNOWLEDGE", "private", "not source evidence")));
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("question"));
            assertEquals(EMPTY, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void directToolInvocationCannotBypassLocalBinding() {
        var tool = new LocalWorkspaceSearchTool(search, library, "workspace");
        for (var other : List.of(new ToolExecution.Binding("other", "local", "workspace-search"),
                new ToolExecution.Binding("workspace", "remote", "workspace-search"),
                new ToolExecution.Binding("workspace", "local", "*"))) {
            assertThrows(IllegalArgumentException.class, () -> tool.execute(new LocalSearchQuery("question"),
                    new ToolExecution.Context(other, () -> false), text -> fail("Output must not escape")));
        }
        verifyNoInteractions(search, library);
    }

    @Test
    void workspaceExistenceIsCheckedBeforeEverySearch() {
        when(search.searchQuestion("workspace", "first", List.of(), false)).thenReturn(List.of());
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(invocation("first")).status());
            when(library.workspace("workspace")).thenReturn(Optional.empty());
            var result = executor.execute(invocation("second"));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
        }
        verify(search).searchQuestion("workspace", "first", List.of(), false);
        verifyNoMoreInteractions(search);
    }

    @Test
    void deletionDuringSearchDiscardsReturnedEvidence() {
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenAnswer(invocation -> {
            when(library.workspace("workspace")).thenReturn(Optional.empty());
            return List.of(hit("ORIGINAL", "private", "must not escape"));
        });
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("question"));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void cancellationDuringSearchDiscardsReturnedEvidence() {
        var cancellation = new ToolExecution.Cancellation();
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenAnswer(invocation -> {
            cancellation.cancel();
            return List.of(hit("ORIGINAL", "private", "must not escape"));
        });
        try (var executor = factory.open("workspace", budget, cancellation)) {
            var result = executor.execute(invocation("question"));
            assertEquals(CANCELLED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void searchFailureReturnsSanitizedFailure() {
        when(search.searchQuestion("workspace", "question", List.of(), false))
                .thenThrow(new IllegalStateException("private credential detail"));
        try (var executor = factory.open("workspace", budget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("question"));
            assertEquals(FAILED, result.status());
            assertEquals("tool_failed", result.reason());
            assertEquals("", result.output());
        }
    }

    @Test
    void blockedCooperativeSearchTimesOutAndReleasesWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        when(search.searchQuestion("workspace", "question", List.of(), false)).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } finally { exited.countDown(); }
            return List.of();
        });
        var shortBudget = new ToolExecution.Budget(1, Duration.ofSeconds(5), Duration.ofMillis(200), 1000, 1000);
        try (var executor = factory.open("workspace", shortBudget, new ToolExecution.Cancellation())) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("question")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var result = future.get(2, TimeUnit.SECONDS);
            assertEquals(TIMEOUT, result.status());
            assertEquals("", result.output());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void longMetadataIsOmittedAndContentIsSubjectToOutputBudget() {
        when(search.searchQuestion("workspace", "question", List.of(), false))
                .thenReturn(List.of(hit("ORIGINAL", "path".repeat(1000), "content".repeat(1000))));
        var smallBudget = new ToolExecution.Budget(1, Duration.ofSeconds(5), Duration.ofSeconds(2), 180, 180);
        try (var executor = factory.open("workspace", smallBudget, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("question"));
            assertEquals(OK, result.status());
            assertEquals(180, result.output().length());
            assertTrue(result.truncated());
        }
    }

    private ToolExecution.Invocation<LocalSearchQuery> invocation(String question) {
        return new ToolExecution.Invocation<>(LocalWorkspaceSearchTool.ID, binding, new LocalSearchQuery(question));
    }

    private static SearchIndex.Hit hit(String kind, String path, String content) {
        return new SearchIndex.Hit(ENTRY_ID, "name", "https://source.invalid?token=source-secret", "", path, content, 1,
                4, 8, true, kind, null, null, List.of(), "UNCHECKED", "hash");
    }
}
