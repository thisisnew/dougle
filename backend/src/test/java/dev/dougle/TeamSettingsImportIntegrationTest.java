package dev.dougle;

import dev.dougle.library.application.LibraryStore;
import dev.dougle.sources.application.SourceService;
import dev.dougle.sources.application.SourceStore;
import dev.dougle.transfer.application.TeamSettingsImportReceipts;
import dev.dougle.transfer.application.TeamSettingsImportService;
import dev.dougle.transfer.application.TeamSettingsImportService.*;
import dev.dougle.transfer.application.TeamSettingsService;
import dev.dougle.transfer.application.TeamSettingsService.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
class TeamSettingsImportIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-team-import-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired TeamSettingsImportService imports;
    @Autowired TeamSettingsService exports;
    @Autowired LibraryStore library;
    @Autowired SourceStore sources;
    @Autowired SourceService sourceService;
    @Autowired TeamSettingsImportReceipts receipts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void appliesViaApiWithoutSyncActivationOrCredentialsAndReplaysAfterRestart() throws Exception {
        String previousActive = library.activeWorkspace();
        var input = new PlanRequest(settings(List.of(new Descriptor("Folder", "LOCAL_FOLDER", null),
                new Descriptor("Web", "URL", null), new Descriptor("Database", "MYSQL", null))), name(),
                List.of(new Mapping(0, DATA.toString()), new Mapping(1, "https://example.test/docs")));
        int before = count("workspace");
        var plan = postJson("import-plan", input, Plan.class);
        assertThat(count("workspace")).isEqualTo(before);
        assertThat(plan.reconnectSourceIndices()).containsExactly(2);
        var request = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        var result = postJson("import-apply", request, Applied.class);
        assertThat(result.sourceIds()).hasSize(2);
        assertThat(result.reconnectSourceIndices()).containsExactly(2);
        assertThat(library.activeWorkspace()).isEqualTo(previousActive);
        assertThat(sources.list(result.workspaceId())).allSatisfy(source -> {
            assertThat(source.pollingEnabled()).isFalse();
            assertThat(source.nextPollAt()).isNull();
            assertThat(source.status()).isEqualTo("NEW");
            assertThat(source.syncedAt()).isNull();
            assertThat(source.pollIntervalSeconds()).isEqualTo(600);
        });
        assertThat(sources.workspacePolling(result.workspaceId()).enabled()).isFalse();
        assertThat(library.entries(result.workspaceId(), "ORIGINAL", null)).isEmpty();
        assertThat(exports.exportSettings(result.workspaceId()).sources()).allMatch(source -> source.location() == null);
        var restarted = new TeamSettingsImportService(library, sources, sourceService, receipts, transactions, clock);
        assertThat(restarted.apply(request)).isEqualTo(result);
        library.deleteWorkspace(result.workspaceId());
        assertThat(restarted.apply(request)).isEqualTo(result);
        assertThat(library.workspace(result.workspaceId())).isEmpty();
    }

    @Test
    void failureRollsBackWorkspaceSourcesPolicyAndReceiptAndCanRetry() throws Exception {
        Path missing = DATA.resolve(UUID.randomUUID().toString());
        String workspaceName = name();
        var plan = imports.plan(new PlanRequest(settings(List.of(new Descriptor("First", "URL", null),
                new Descriptor("Second", "LOCAL_FOLDER", null))), workspaceName,
                List.of(new Mapping(0, "https://example.test/docs"), new Mapping(1, missing.toString()))));
        var request = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        int workspaceCount = count("workspace");
        int sourceCount = count("source");
        int policyCount = count("workspace_source_polling");
        int receiptCount = count("team_settings_import_receipt");
        assertThatThrownBy(() -> imports.apply(request)).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        assertThat(count("workspace")).isEqualTo(workspaceCount);
        assertThat(count("source")).isEqualTo(sourceCount);
        assertThat(count("workspace_source_polling")).isEqualTo(policyCount);
        assertThat(count("team_settings_import_receipt")).isEqualTo(receiptCount);
        Files.createDirectory(missing);
        assertThat(imports.apply(request).sourceIds()).hasSize(2);
    }

    @Test
    void bindsDigestAndRejectsRequestIdReuseWithoutOverwriting() {
        var plan = imports.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        String requestId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> imports.apply(new ApplyRequest(requestId, plan.previewId(), "0".repeat(64)))).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var result = imports.apply(new ApplyRequest(requestId, plan.previewId(), plan.digest()));
        var different = imports.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        assertThatThrownBy(() -> imports.apply(new ApplyRequest(requestId, different.previewId(), different.digest()))).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        assertThat(library.workspace(result.workspaceId())).isPresent();
        assertThatThrownBy(() -> imports.plan(new PlanRequest(settings(List.of()), plan.workspaceName(), List.of()))).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void concurrentDuplicateApplyCreatesOnlyOneWorkspace() throws Exception {
        var plan = imports.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        var request = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        int before = count("workspace");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> imports.apply(request));
            var second = executor.submit(() -> imports.apply(request));
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(count("workspace")).isEqualTo(before + 1);
    }

    @Test
    void consumedPreviewRejectsNewRequestAfterDeletionAndRestartButOriginalRetrySucceeds() {
        var plan = imports.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        var request = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        var applied = imports.apply(request);
        library.deleteWorkspace(applied.workspaceId());
        int before = count("workspace");
        int receiptsBefore = count("team_settings_import_receipt");
        var replay = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        assertThatThrownBy(() -> imports.apply(replay))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class)
                .hasMessageContaining("이미 적용한 미리보기");
        var restarted = new TeamSettingsImportService(library, sources, sourceService, receipts, transactions, clock);
        assertThatThrownBy(() -> restarted.apply(replay))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class)
                .hasMessageContaining("이미 적용한 미리보기");
        assertThat(imports.apply(request)).isEqualTo(applied);
        assertThat(restarted.apply(request)).isEqualTo(applied);
        assertThat(count("workspace")).isEqualTo(before);
        assertThat(count("team_settings_import_receipt")).isEqualTo(receiptsBefore);
    }

    @Test
    void concurrentDifferentRequestsConsumePreviewOnlyOnce() throws Exception {
        var plan = imports.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        var start = new java.util.concurrent.CountDownLatch(1);
        var firstRequest = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        var secondRequest = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        int before = count("workspace");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyOutcome(firstRequest, start));
            var second = executor.submit(() -> applyOutcome(secondRequest, start));
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("workspace")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_settings_import_receipt WHERE preview_id=?", Integer.class, plan.previewId())).isEqualTo(1);
    }

    @Test
    void planningWaitingForSingleDatabaseConnectionDoesNotBlockApplyingThread() throws Exception {
        var connectionHeld = new java.util.concurrent.CountDownLatch(1);
        var planningAtDatabase = new java.util.concurrent.CountDownLatch(1);
        LibraryStore observedLibrary = mock(LibraryStore.class, org.mockito.AdditionalAnswers.delegatesTo(library));
        TeamSettingsImportReceipts observedReceipts = mock(TeamSettingsImportReceipts.class, org.mockito.AdditionalAnswers.delegatesTo(receipts));
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("team-plan-regression")) planningAtDatabase.countDown();
            return library.workspaces();
        }).when(observedLibrary).workspaces();
        doAnswer(invocation -> {
            boolean reserved = receipts.reserve(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            connectionHeld.countDown();
            assertThat(planningAtDatabase.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return reserved;
        }).when(observedReceipts).reserve(anyString(), anyString(), anyString());
        var service = new TeamSettingsImportService(observedLibrary, sources, sourceService, observedReceipts, transactions, clock);
        var preview = service.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        var request = new ApplyRequest(UUID.randomUUID().toString(), preview.previewId(), preview.digest());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            var applying = executor.submit(() -> service.apply(request));
            assertThat(connectionHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var planning = executor.submit(() -> {
                Thread.currentThread().setName("team-plan-regression");
                return service.plan(new PlanRequest(settings(List.of()), name(), List.of()));
            });
            assertThat(applying.get(5, java.util.concurrent.TimeUnit.SECONDS).workspaceId()).isNotBlank();
            assertThat(planning.get(5, java.util.concurrent.TimeUnit.SECONDS).previewId()).isNotBlank();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private int applyOutcome(ApplyRequest request, java.util.concurrent.CountDownLatch start) throws InterruptedException {
        start.await();
        try { imports.apply(request); return 200; }
        catch (dev.dougle.library.application.LibraryException exception) { return exception.status(); }
    }

    @Test
    void expiredOrRestartedUnappliedPreviewCannotWrite() {
        Clock controlled = mock(Clock.class);
        when(controlled.millis()).thenReturn(1_000L);
        var service = new TeamSettingsImportService(library, sources, sourceService, receipts, transactions, controlled);
        var plan = service.plan(new PlanRequest(settings(List.of()), name(), List.of()));
        var request = new ApplyRequest(UUID.randomUUID().toString(), plan.previewId(), plan.digest());
        int before = count("team_settings_import_receipt");
        when(controlled.millis()).thenReturn(601_000L);
        assertThatThrownBy(() -> service.apply(request)).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        assertThatThrownBy(() -> imports.apply(request)).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        assertThat(count("team_settings_import_receipt")).isEqualTo(before);
    }

    @Test
    void validatesMappingsAndNeverCopiesMysqlCredentials() {
        for (var input : List.of(
                new PlanRequest(settings(List.of()), "x".repeat(21), List.of()),
                new PlanRequest(settings(List.of(new Descriptor("Web", "URL", null))), name(), List.of()),
                new PlanRequest(settings(List.of(new Descriptor("Web", "URL", null))), name(), List.of(new Mapping(0, "https://example.test?token=secret"))),
                new PlanRequest(settings(List.of(new Descriptor("DB", "MYSQL", null))), name(), List.of(new Mapping(0, "mysql://secret"))),
                new PlanRequest(settings(List.of(new Descriptor("Web", "URL", null))), name(), List.of(new Mapping(0, "https://example.test"), new Mapping(0, "https://example.test"))))) {
            assertThatThrownBy(() -> imports.plan(input)).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        }
    }

    @Test
    void apiRejectsMissingClientForeignOriginUnknownFieldsAndOversizedPayload() throws Exception {
        String input = json.writeValueAsString(new PlanRequest(settings(List.of()), name(), List.of()));
        mvc.perform(post("/api/team-settings/import-plan").contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isForbidden());
        mvc.perform(post("/api/team-settings/import-apply").header("X-Forge-Client", "1").header("Origin", "https://evil.test")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        for (String invalid : List.of(input.replace("\"mappings\":[]", "\"mappings\":[],\"credentials\":\"secret\""),
                input.replace("\"mappings\":[]", "\"mappings\":[],\"mappings\":[]"), input.replace("\"version\":1", "\"version\":\"1\""),
                input.replace("\"version\":1", "\"version\":1.5"), "{}", input + "{}")) {
            mvc.perform(post("/api/team-settings/import-plan").header("X-Forge-Client", "1").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/team-settings/import-apply").header("X-Forge-Client", "1").contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(256 * 1024 + 1))).andExpect(status().isPayloadTooLarge());
    }

    private <Value> Value postJson(String endpoint, Object input, Class<Value> type) throws Exception {
        var result = mvc.perform(post("/api/team-settings/" + endpoint).header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk()).andReturn();
        return json.readValue(result.getResponse().getContentAsString(), type);
    }
    private Settings settings(List<Descriptor> descriptors) { return new Settings(1, "Exported", 600, descriptors); }
    private String name() { return "import-" + UUID.randomUUID().toString().substring(0, 8); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
