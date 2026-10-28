package dev.dougle;

import dev.dougle.conversation.application.AnswerJobStore;
import dev.dougle.conversation.application.ChatThreadService;
import dev.dougle.conversation.domain.AnswerJob;
import dev.dougle.ingestion.application.AnalysisStore;
import dev.dougle.ingestion.application.DocumentAnalyzer;
import dev.dougle.library.application.LibraryService;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.search.application.EvidenceBundleStore;
import dev.dougle.search.application.EvidenceBundleValidator;
import dev.dougle.search.domain.ContentDigest;
import dev.dougle.search.domain.EvidenceBundle;
import dev.dougle.sources.application.SourceStore;
import dev.dougle.transfer.application.WorkspaceArchiveData;
import dev.dougle.transfer.application.WorkspaceArchiveData.*;
import dev.dougle.transfer.application.WorkspaceArchivePersistence;
import dev.dougle.transfer.application.WorkspaceArchiveReceipts;
import dev.dougle.transfer.application.WorkspaceArchiveService;
import dev.dougle.transfer.domain.TransferArchiveCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceArchiveIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-workspace-archive-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired WorkspaceArchiveService archives;
    @Autowired LibraryService library;
    @Autowired LibraryStore store;
    @Autowired AnalysisStore analyses;
    @Autowired DocumentAnalyzer analyzer;
    @Autowired WorkspaceArchiveReceipts receipts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired java.time.Clock clock;
    @Autowired EvidenceBundleStore bundles;
    @Autowired EvidenceBundleValidator validator;
    @Autowired ChatThreadService threads;
    @Autowired AnswerJobStore jobs;
    @Autowired SourceStore sources;
    @Autowired JdbcTemplate jdbc;
    @Autowired dev.dougle.search.application.SearchService search;
    @Autowired MockMvc mvc;
    @Autowired dev.dougle.attachment.application.AttachmentService attachments;
    @MockitoSpyBean WorkspaceArchivePersistence persistence;
    final JsonMapper json = JsonMapper.builder().build();
    final TransferArchiveCodec codec = new TransferArchiveCodec();
    final String content = "Synthetic Strategy delegates to a replaceable implementation.\n";

    @org.junit.jupiter.api.BeforeEach
    void clearSessionPreviews() {
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(archives, "stages")).clear();
    }

    @Test
    void versionTwoPreservesExtractiveHorizonAndRemapsAllReferencesIncludingSearchCaptures() throws Exception {
        var fixture = fixture();
        search.refresh();
        var prior = threads.get(fixture.workspaceId(), fixture.first().payload().chatContext().threadId()).bundles().getLast();
        String threadId = prior.payload().chatContext().threadId();
        for (int index = 0; index < 35; index++) {
            prior = threads.create(fixture.workspaceId(), new ChatThreadService.Create("extractive-" + index, threadId, prior.id(), "Strategy " + index, List.of()));
        }
        var captured = threads.createWithRetrieval(fixture.workspaceId(), new ChatThreadService.Create("extractive-search", threadId, prior.id(), prior.payload().question(), List.of()), "Strategy");
        var original = captured.payload().chatContext().extractive();
        byte[] bytes = archives.exportArchive(fixture.workspaceId());
        var catalog = json.readTree(codec.read(bytes).documents().get(WorkspaceArchiveService.CATALOG_KEY));
        assertThat(catalog.path("version").asInt()).isEqualTo(2);
        assertThat(catalog.toString()).doesNotContain(captured.id(), prior.id());
        var preview = archives.preview(bytes, name());
        var result = archives.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest()));
        var restored = threads.get(result.workspaceId(), result.threadIds().getFirst());
        var last = restored.bundles().getLast();
        var memory = last.payload().chatContext().extractive();
        assertThat(memory.turnKind()).isEqualTo("SEARCH_CAPTURE");
        assertThat(memory.totalQuestions()).isEqualTo(original.totalQuestions());
        assertThat(memory.omittedQuestions()).isEqualTo(original.omittedQuestions()).isGreaterThan(0);
        assertThat(memory.questions()).extracting(EvidenceBundle.UserQuestion::question)
                .containsExactlyElementsOf(original.questions().stream().map(EvidenceBundle.UserQuestion::question).toList());
        for (var question : memory.questions()) {
            var source = restored.bundles().stream().filter(bundle -> bundle.id().equals(question.bundleId())).findFirst().orElseThrow();
            assertThat(question.bundleDigest()).isEqualTo(source.digest());
            assertThat(question.question()).isEqualTo(source.payload().question());
        }
        assertThat(memory.questions()).extracting(EvidenceBundle.UserQuestion::bundleId)
                .doesNotContainAnyElementsOf(original.questions().stream().map(EvidenceBundle.UserQuestion::bundleId).toList());
        assertThat(archives.preview(archives.exportArchive(result.workspaceId()), name()).turns()).isEqualTo(restored.bundles().size());
    }

    @Test
    void versionTwoRejectsForgedExtractiveReferenceInsteadOfDroppingMemory() throws Exception {
        var fixture = fixture();
        search.refresh();
        var prior = threads.get(fixture.workspaceId(), fixture.first().payload().chatContext().threadId()).bundles().getLast();
        threads.create(fixture.workspaceId(), new ChatThreadService.Create("memory-forge", prior.payload().chatContext().threadId(), prior.id(), "Strategy", List.of()));
        var documents = new HashMap<>(codec.read(archives.exportArchive(fixture.workspaceId())).documents());
        var catalog = json.readTree(documents.get(WorkspaceArchiveService.CATALOG_KEY));
        var turns = catalog.path("threads").get(0).path("turns");
        var memory = turns.get(turns.size() - 1).path("extractive");
        ((tools.jackson.databind.node.ObjectNode) memory.path("questions").get(0)).put("bundleDigest", "forged");
        documents.put(WorkspaceArchiveService.CATALOG_KEY, json.writeValueAsBytes(catalog));
        byte[] tampered = codec.write(documents);
        assertThatThrownBy(() -> archives.preview(tampered, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void rejectsRootCaptureAndCaptureChangingOriginalQuestionInVersionTwo() throws Exception {
        var fixture = fixture();
        search.refresh();
        var prior = threads.get(fixture.workspaceId(), fixture.first().payload().chatContext().threadId()).bundles().getLast();
        threads.create(fixture.workspaceId(), new ChatThreadService.Create("capture-forge", prior.payload().chatContext().threadId(), prior.id(), "Strategy", List.of()));
        var original = codec.read(archives.exportArchive(fixture.workspaceId())).documents();
        for (boolean root : List.of(true, false)) {
            var documents = new HashMap<>(original);
            var catalog = json.readTree(documents.get(WorkspaceArchiveService.CATALOG_KEY));
            var turns = catalog.path("threads").get(0).path("turns");
            var turn = (tools.jackson.databind.node.ObjectNode) turns.get(root ? 0 : turns.size() - 1);
            if (root) {
                var selection = dev.dougle.search.application.ContextPrompt.select(List.of(), List.of(), List.of(turn.path("retrievalQuery").asText()), true);
                turn.set("extractive", json.valueToTree(selection.extractive()));
            } else {
                ((tools.jackson.databind.node.ObjectNode) turn.path("extractive")).put("turnKind", "SEARCH_CAPTURE");
                turn.put("question", "changed target");
            }
            documents.put(WorkspaceArchiveService.CATALOG_KEY, json.writeValueAsBytes(catalog));
            byte[] tampered = codec.write(documents);
            assertThatThrownBy(() -> archives.preview(tampered, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        }
    }

    @Test
    void apiRoundTripPreservesMaterialsAnswersAndRebindsHistoryEvidenceWithoutApprovals() throws Exception {
        var fixture = fixture();
        String active = store.activeWorkspace();
        byte[] archive = mvc.perform(get("/api/workspace-archives/export/" + fixture.workspaceId()))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(codec.read(archive).documents().get(WorkspaceArchiveService.CATALOG_KEY), StandardCharsets.UTF_8))
                .doesNotContain("/external/private/token", fixture.approvalId(), fixture.workspaceId());
        int before = count("workspace");
        var response = mvc.perform(post("/api/workspace-archives/import-preview").param("workspaceName", name())
                .header("X-Forge-Client", "1").contentType("application/zip").content(archive))
                .andExpect(status().isOk()).andReturn();
        var preview = json.readValue(response.getResponse().getContentAsString(), WorkspaceArchiveService.Preview.class);
        assertThat(preview.materials()).isEqualTo(2);
        assertThat(preview.turns()).isEqualTo(2);
        assertThat(preview.answers()).isEqualTo(1);
        assertThat(count("workspace")).isEqualTo(before);
        var request = new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest());
        var appliedResponse = mvc.perform(post("/api/workspace-archives/import-apply").header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        var result = json.readValue(appliedResponse.getResponse().getContentAsString(), WorkspaceArchiveService.Result.class);
        assertThat(result.workspaceId()).isNotEqualTo(fixture.workspaceId());
        assertThat(store.activeWorkspace()).isEqualTo(active);
        assertThat(archives.apply(request)).isEqualTo(result);
        assertThat(count("workspace")).isEqualTo(before + 1);
        var folder = store.entries(result.workspaceId(), "ORIGINAL", null).getFirst();
        var file = store.entries(result.workspaceId(), "ORIGINAL", folder.id()).getFirst();
        assertThat(store.content(file.id())).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        assertThat(file.createdAt()).isEqualTo(store.entry(fixture.workspaceId(), fixture.fileId()).orElseThrow().createdAt());
        assertThat(analyses.find(result.workspaceId(), file.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM search_outbox WHERE entry_id=?", Integer.class, file.id())).isGreaterThan(0);
        assertThat(sources.list(result.workspaceId())).isEmpty();
        assertThat(sources.workspacePolling(result.workspaceId()).enabled()).isFalse();
        var imported = threads.get(result.workspaceId(), result.threadIds().getFirst());
        var first = imported.bundles().getFirst();
        var second = imported.bundles().getLast();
        assertThat(first.digest()).isNotEqualTo(fixture.first().digest());
        assertThat(first.createdAt()).isEqualTo(fixture.first().createdAt());
        assertThat(second.payload().chatContext().parentBundleId()).isEqualTo(first.id());
        assertThat(second.payload().chatContext().history().getFirst().bundleDigest()).isEqualTo(first.digest());
        assertThat(second.payload().chatContext().history().getFirst().bundleId()).isEqualTo(first.id());
        assertThat(first.payload().items().getFirst().entryId()).isEqualTo(file.id());
        assertThat(first.payload().items().getFirst().sourceId()).isNull();
        assertThat(first.payload().items().getFirst().path()).isEqualTo(file.name());
        assertThat(validator.check(result.workspaceId(), first.id()).eligible()).isTrue();
        assertThat(imported.jobs()).singleElement().satisfies(job -> {
            assertThat(job.status()).isEqualTo("SUCCEEDED");
            assertThat(job.answer()).isEqualTo("Synthetic completed answer.");
            assertThat(job.approvalId()).isNotEqualTo(fixture.approvalId());
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval WHERE bundle_id IN (SELECT id FROM evidence_bundle WHERE workspace_id=?)", Integer.class, result.workspaceId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_job WHERE workspace_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, result.workspaceId())).isZero();
        store.deleteWorkspace(result.workspaceId());
        assertThat(archives.apply(request)).isEqualTo(result);
        assertThat(store.workspace(result.workspaceId())).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "CODEX", "CLAUDE" })
    void defaultModelAnswersRoundTripWithoutInventingModelOrDroppingMultiTurnHistory(String provider) throws Exception {
        var fixture = fixture();
        jdbc.update("UPDATE answer_job SET provider=?,requested_model='' WHERE workspace_id=?", provider, fixture.workspaceId());
        var original = threads.get(fixture.workspaceId(), fixture.first().payload().chatContext().threadId());
        var firstArchive = archives.exportArchive(fixture.workspaceId());
        var catalog = json.readTree(codec.read(firstArchive).documents().get(WorkspaceArchiveService.CATALOG_KEY));
        assertThat(catalog.path("threads").get(0).path("turns").get(0).path("answers").get(0).path("model").asString()).isEmpty();
        for (int round = 0; round < 2; round++) {
            var preview = archives.preview(firstArchive, name());
            var imported = archives.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest()));
            var restored = threads.get(imported.workspaceId(), imported.threadIds().getFirst());
            assertThat(restored.bundles()).extracting(bundle -> bundle.payload().question())
                    .containsExactlyElementsOf(original.bundles().stream().map(bundle -> bundle.payload().question()).toList());
            var first = restored.bundles().getFirst();
            var followup = restored.bundles().getLast().payload().chatContext();
            assertThat(followup.parentBundleId()).isEqualTo(first.id());
            assertThat(followup.history()).containsExactly(new EvidenceBundle.ChatTurn(first.id(), first.digest(),
                    first.payload().question(), "Synthetic completed answer."));
            assertThat(restored.jobs()).singleElement().satisfies(job -> {
                assertThat(job.provider()).isEqualTo(provider);
                assertThat(job.requestedModel()).isEmpty();
                assertThat(job.answer()).isEqualTo(original.jobs().getFirst().answer());
                assertThat(job.status()).isEqualTo("SUCCEEDED");
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM answer_approval WHERE bundle_id IN (SELECT id FROM evidence_bundle WHERE workspace_id=?)",
                    Integer.class, imported.workspaceId())).isZero();
            firstArchive = archives.exportArchive(imported.workspaceId());
        }
        assertThat(jobs.list(fixture.workspaceId()).getFirst().requestedModel()).isEmpty();
    }

    @Test
    void defaultModelCompatibilityDoesNotAcceptNullWhitespaceOrOversizedModelMetadata() throws Exception {
        var fixture = fixture();
        var documents = codec.read(archives.exportArchive(fixture.workspaceId())).documents();
        int before = count("workspace");
        for (String model : new String[] { null, " ", "\t", "m".repeat(121) }) {
            var catalog = json.readTree(documents.get(WorkspaceArchiveService.CATALOG_KEY));
            var answer = (tools.jackson.databind.node.ObjectNode) catalog.path("threads").get(0).path("turns").get(0).path("answers").get(0);
            if (model == null) answer.putNull("model"); else answer.put("model", model);
            var changed = new HashMap<>(documents);
            changed.put(WorkspaceArchiveService.CATALOG_KEY, json.writeValueAsBytes(catalog));
            byte[] archive = codec.write(changed);
            assertThatThrownBy(() -> archives.preview(archive, name()))
                    .isInstanceOf(dev.dougle.library.application.LibraryException.class);
        }
        assertThat(count("workspace")).isEqualTo(before);
    }

    @Test
    void attachedOperationalTextBlocksExportWithoutSilentlyDroppingEvidenceOrChangingSource() {
        var fixture = fixture();
        search.refresh();
        byte[] text = "Synthetic operational read\nservice=example\nresult=healthy\n".getBytes(StandardCharsets.UTF_8);
        var attachment = attachments.upload(fixture.workspaceId(), "datadog-synthetic.txt", text);
        var thread = threads.get(fixture.workspaceId(), fixture.first().payload().chatContext().threadId());
        var attached = threads.create(fixture.workspaceId(), new ChatThreadService.Create("operational-archive", thread.id(),
                thread.bundles().getLast().id(), "Explain synthetic operational evidence", List.of(attachment.id())));
        var before = attachments.get(fixture.workspaceId(), attachment.id());
        int workspaces = count("workspace");
        assertThat(before.bundleId()).isEqualTo(attached.id());
        assertThatThrownBy(() -> archives.exportArchive(fixture.workspaceId()))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class).hasMessageContaining("첨부");
        assertThat(attachments.get(fixture.workspaceId(), attachment.id()).content()).isEqualTo(text);
        assertThat(attachments.get(fixture.workspaceId(), attachment.id()).bundleId()).isEqualTo(attached.id());
        assertThat(bundles.find(fixture.workspaceId(), attached.id())).contains(attached);
        assertThat(count("workspace")).isEqualTo(workspaces);
    }

    @Test
    void rejectsChecksummedButInvalidEvidenceAndFutureHistoryWithoutWrites() throws Exception {
        var fixture = fixture();
        var decoded = codec.read(archives.exportArchive(fixture.workspaceId())).documents();
        var original = json.readValue(decoded.get(WorkspaceArchiveService.CATALOG_KEY), WorkspaceArchiveData.class);
        var thread = original.threads().getFirst();
        var turn = thread.turns().getFirst();
        var evidence = turn.evidence().getFirst();
        var badEvidence = new Evidence(evidence.materialKey(), evidence.contentHash(), evidence.textHash(), 1, 1, "Fabricated quote");
        var badTurn = new Turn(turn.key(), turn.createdAt(), turn.question(), turn.retrievalQuery(), List.of(badEvidence), turn.history(), turn.answers());
        int before = count("workspace");
        byte[] corrupt = replaceCatalog(decoded, new WorkspaceArchiveData(1, original.workspaceName(), original.materials(), List.of(new ThreadData(List.of(badTurn)))));
        assertThatThrownBy(() -> archives.preview(corrupt, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var future = new Turn(turn.key(), turn.createdAt(), turn.question(), turn.retrievalQuery(), turn.evidence(),
                List.of(new History(thread.turns().getLast().key(), "future", null)), turn.answers());
        byte[] invalidHistory = replaceCatalog(decoded, new WorkspaceArchiveData(1, original.workspaceName(), original.materials(), List.of(new ThreadData(List.of(future, thread.turns().getLast())))));
        assertThatThrownBy(() -> archives.preview(invalidHistory, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        assertThat(count("workspace")).isEqualTo(before);
    }

    @Test
    void rejectsUnknownVersionPathsMissingPayloadAndExternalFields() throws Exception {
        var fixture = fixture();
        var decoded = codec.read(archives.exportArchive(fixture.workspaceId())).documents();
        var original = json.readValue(decoded.get(WorkspaceArchiveService.CATALOG_KEY), WorkspaceArchiveData.class);
        byte[] wrongVersion = replaceCatalog(decoded, new WorkspaceArchiveData(99, original.workspaceName(), original.materials(), original.threads()));
        assertThatThrownBy(() -> archives.preview(wrongVersion, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var path = original.materials().getFirst();
        var modified = new java.util.ArrayList<>(original.materials());
        modified.set(0, new Material(path.key(), path.parentKey(), "../outside", path.kind(), path.createdAt()));
        byte[] badName = replaceCatalog(decoded, new WorkspaceArchiveData(1, original.workspaceName(), modified, original.threads()));
        assertThatThrownBy(() -> archives.preview(badName, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var extra = new HashMap<>(decoded);
        String unknown = json.writeValueAsString(original).replaceFirst("\\{", "{\"credentials\":\"secret\",");
        extra.put(WorkspaceArchiveService.CATALOG_KEY, unknown.getBytes(StandardCharsets.UTF_8));
        byte[] unknownFields = codec.write(extra);
        assertThatThrownBy(() -> archives.preview(unknownFields, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var missing = new HashMap<>(decoded);
        missing.remove(original.materials().stream().filter(material -> material.kind().equals("FILE")).findFirst().orElseThrow().key());
        byte[] missingFile = codec.write(missing);
        assertThatThrownBy(() -> archives.preview(missingFile, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var output = new java.io.ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) { zip.putNextEntry(new ZipEntry("../escape")); zip.write(1); zip.closeEntry(); }
        assertThatThrownBy(() -> archives.preview(output.toByteArray(), name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void rejectsTamperedBytesAndStaleOriginalEvidence() throws Exception {
        var fixture = fixture();
        byte[] bytes = archives.exportArchive(fixture.workspaceId());
        bytes[0] = 0;
        assertThatThrownBy(() -> archives.preview(bytes, name())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        jdbc.update("UPDATE entry SET content=? WHERE id=?", "changed".getBytes(StandardCharsets.UTF_8), fixture.fileId());
        assertThatThrownBy(() -> archives.exportArchive(fixture.workspaceId())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void atomicFailureRollsBackAllImportedRowsAndAllowsRetry() {
        var fixture = fixture();
        var preview = archives.preview(archives.exportArchive(fixture.workspaceId()), name());
        var request = new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest());
        int workspaces = count("workspace");
        int entries = count("entry");
        int evidence = count("evidence_bundle");
        int answers = count("answer_job");
        int receiptCount = count("workspace_archive_import_receipt");
        doThrow(new IllegalStateException("synthetic write failure")).when(persistence).saveBundle(anyString(), anyString(), any());
        try {
            assertThatThrownBy(() -> archives.apply(request)).isInstanceOf(IllegalStateException.class);
            assertThat(count("workspace")).isEqualTo(workspaces);
            assertThat(count("entry")).isEqualTo(entries);
            assertThat(count("evidence_bundle")).isEqualTo(evidence);
            assertThat(count("answer_job")).isEqualTo(answers);
            assertThat(count("workspace_archive_import_receipt")).isEqualTo(receiptCount);
        } finally { doCallRealMethod().when(persistence).saveBundle(anyString(), anyString(), any()); }
        assertThat(archives.apply(request).workspaceId()).isNotBlank();
    }

    @Test
    void durableReceiptReplaysAfterRestartAndDeletionButRejectsNewRequestForConsumedPreview() {
        var fixture = fixture();
        var initial = freshService();
        var preview = initial.preview(initial.exportArchive(fixture.workspaceId()), name());
        var request = new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest());
        var result = initial.apply(request);
        var restarted = freshService();
        int before = count("workspace");
        assertThat(restarted.apply(request)).isEqualTo(result);
        assertThat(count("workspace")).isEqualTo(before);
        store.deleteWorkspace(result.workspaceId());
        assertThat(restarted.apply(request)).isEqualTo(result);
        assertThat(store.workspace(result.workspaceId())).isEmpty();
        assertThatThrownBy(() -> initial.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest())))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class).hasMessageContaining("이미 사용한");
        assertThatThrownBy(() -> restarted.apply(new WorkspaceArchiveService.Apply(request.requestId(), preview.previewId(), "f".repeat(64))))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class);
        var unapplied = initial.preview(initial.exportArchive(fixture.workspaceId()), name());
        assertThatThrownBy(() -> restarted.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), unapplied.previewId(), unapplied.digest())))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class).hasMessageContaining("만료");
    }

    @Test
    void concurrentSameRequestReturnsOneDurableImportAndNewRequestCannotConsumeAgain() throws Exception {
        var fixture = fixture();
        var service = freshService();
        var preview = service.preview(service.exportArchive(fixture.workspaceId()), name());
        var request = new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest());
        int before = count("workspace");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.apply(request));
            var second = executor.submit(() -> service.apply(request));
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(count("workspace")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_archive_import_receipt WHERE preview_id=?", Integer.class, preview.previewId())).isEqualTo(1);
        assertThatThrownBy(() -> service.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest())))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void rejectsActiveJobsAndUnsupportedKnowledgeEvidence() {
        var fixture = fixture();
        jdbc.update("UPDATE answer_job SET status='RUNNING' WHERE workspace_id=?", fixture.workspaceId());
        assertThatThrownBy(() -> archives.exportArchive(fixture.workspaceId())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        jdbc.update("UPDATE answer_job SET status='SUCCEEDED' WHERE workspace_id=?", fixture.workspaceId());
        bundles.save(fixture.workspaceId(), new EvidenceBundle.Payload("knowledge", List.of(), true, "NO_EVIDENCE", 0, 12000, List.of(), List.of()));
        assertThatThrownBy(() -> archives.exportArchive(fixture.workspaceId())).isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test
    void requiresBoundPreviewNewNameAndSameOriginAndRejectsOversizedBody() throws Exception {
        var fixture = fixture();
        byte[] archive = archives.exportArchive(fixture.workspaceId());
        String target = name();
        var preview = archives.preview(archive, target);
        assertThatThrownBy(() -> archives.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), "0".repeat(64)))).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        library.createWorkspace(target);
        assertThatThrownBy(() -> archives.apply(new WorkspaceArchiveService.Apply(UUID.randomUUID().toString(), preview.previewId(), preview.digest()))).isInstanceOf(dev.dougle.library.application.LibraryException.class);
        mvc.perform(post("/api/workspace-archives/import-preview").param("workspaceName", name()).contentType("application/zip").content(archive)).andExpect(status().isForbidden());
        mvc.perform(post("/api/workspace-archives/import-apply").header("X-Forge-Client", "1").header("Origin", "https://evil.test")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/workspace-archives/import-preview").param("workspaceName", name()).header("X-Forge-Client", "1")
                .contentType("application/zip").content(new byte[TransferArchiveCodec.MAX_ARCHIVE_BYTES + 1])).andExpect(status().isPayloadTooLarge());
    }

    private record Fixture(String workspaceId, String fileId, EvidenceBundle first, String approvalId) {}

    @Test
    void credentialExportIsBlockedWithoutChangingOriginalFilesOrLegacyBundles() {
        String workspace = library.createWorkspace(name()).id();
        byte[] secret = "password=Synthet1c-Only-Value-7823".getBytes(StandardCharsets.UTF_8);
        var file = library.upload(workspace, null, "synthetic.txt", secret);
        assertThatThrownBy(() -> archives.exportArchive(workspace))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class).hasMessageContaining("인증정보");
        assertThat(store.content(file.id())).isEqualTo(secret);
        var fixture = fixture();
        var payload = fixture.first().payload();
        var legacy = bundles.save(fixture.workspaceId(), new EvidenceBundle.Payload("token=Synthet1c-Only-Value-7823",
                List.of(), false, "READY", payload.usedCharacters(), 12000, payload.items(), List.of()));
        assertThatThrownBy(() -> archives.exportArchive(fixture.workspaceId()))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class).hasMessageContaining("인증정보");
        assertThat(bundles.find(fixture.workspaceId(), legacy.id()).orElseThrow()).isEqualTo(legacy);
        assertThat(store.content(fixture.fileId())).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }
    private Fixture fixture() {
        String workspace = library.createWorkspace(name()).id();
        String folder = library.createFolder(workspace, null, "Synthetic folder").id();
        String file = library.upload(workspace, folder, "strategy.txt", content.getBytes(StandardCharsets.UTF_8)).id();
        var analysis = analyses.find(workspace, file).orElseThrow();
        var item = new EvidenceBundle.Item("R1", "ORIGINAL", file, null, null, "strategy.txt", null, "/external/private/token", null,
                ContentDigest.of(content), ContentDigest.of(analysis.text()), 1, 1, analysis.text(), List.of());
        String threadId = UUID.randomUUID().toString();
        var first = bundles.save(workspace, new EvidenceBundle.Payload("What is Strategy?", List.of(), false, "READY", content.length(), 12000,
                List.of(item), List.of(), new EvidenceBundle.ChatContext(threadId, null, UUID.randomUUID().toString(), "Strategy", List.of())));
        String approvalId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jobs.create(new AnswerJob(UUID.randomUUID().toString(), workspace, first.id(), approvalId, "CODEX", "synthetic-model", first.digest(), "SUCCEEDED",
                "Synthetic completed answer.", null, now, now));
        bundles.save(workspace, new EvidenceBundle.Payload("Explain the delegate", List.of(), false, "READY", content.length(), 12000, List.of(item), List.of(),
                new EvidenceBundle.ChatContext(threadId, first.id(), UUID.randomUUID().toString(), "delegate", List.of(
                        new EvidenceBundle.ChatTurn(first.id(), first.digest(), first.payload().question(), "Synthetic completed answer.")))));
        return new Fixture(workspace, file, first, approvalId);
    }
    private byte[] replaceCatalog(java.util.Map<UUID, byte[]> original, WorkspaceArchiveData catalog) throws Exception {
        var entries = new HashMap<>(original);
        entries.put(WorkspaceArchiveService.CATALOG_KEY, json.writeValueAsBytes(catalog));
        return codec.write(entries);
    }
    private String name() { return "archive-" + UUID.randomUUID().toString().substring(0, 8); }
    private WorkspaceArchiveService freshService() {
        return new WorkspaceArchiveService(store, sources, threads, jobs, persistence, receipts, analyzer, analyses, transactions, clock);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
}
