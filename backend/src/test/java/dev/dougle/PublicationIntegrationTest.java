package dev.dougle;

import dev.dougle.knowledge.application.KnowledgeService;
import dev.dougle.knowledge.application.PublicationService;
import dev.dougle.knowledge.domain.Knowledge.*;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PublicationIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-publication-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired KnowledgeService knowledge;
    @Autowired PublicationService publication;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    String workspace;
    String entry;
    String card;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("가상 검토").id();
        entry = library.upload(workspace, null, "notes.txt", "가상 온도 21도".getBytes(StandardCharsets.UTF_8)).id();
        card = knowledge.create(workspace, "관측", "가상 온도는 21도다. [E1]", List.of(new Citation(entry, 1, 1))).card().id();
    }

    void review(int version) { publication.review(workspace, card, version, "가상 검토자", "제목과 온도 수치를 인용 원문과 대조함", true, true); }
    void fails(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LibraryException.class,
                error -> assertThat(error.status()).isEqualTo(status));
    }

    @Test
    void reviewAndPublicationAreSeparateAndNeverMutateVersion() {
        Document before = knowledge.get(workspace, card, null);
        fails(409, () -> publication.publish(workspace, card, 1, 0));
        review(1);
        assertThat(publication.state(workspace, card).publication()).isNull();
        fails(404, () -> publication.published(workspace, card));
        assertThat(publication.publish(workspace, card, 1, 0).publication().version()).isEqualTo(1);
        assertThat(publication.published(workspace, card)).isEqualTo(before);
        fails(409, () -> review(1));
        fails(409, () -> publication.publish(workspace, card, 1, 1));
    }

    @Test
    void requiresBothExplicitAttestationsAndReviewDetails() {
        fails(400, () -> publication.review(workspace, card, 1, "검토자", "기록", false, true));
        fails(400, () -> publication.review(workspace, card, 1, "검토자", "기록", true, false));
        fails(400, () -> publication.review(workspace, card, 1, " ", "기록", true, true));
        fails(400, () -> publication.review(workspace, card, 1, "검토자", " ", true, true));
        assertThat(publication.state(workspace, card).review()).isNull();
    }

    @Test
    void rejectsMissingAndInvalidCitationIdsWithoutCallingThemSemanticVerification() {
        for (String text : List.of("인용 없음", "본문 [E0]", "본문 [E2]", "본문 [E99999999999999999]", "본문 [Ex]")) {
            int version = knowledge.get(workspace, card, null).card().latestVersion();
            knowledge.revise(workspace, card, version, "관측", text, null);
            fails(400, () -> review(version + 1));
        }
    }

    @Test
    void editsRequireNewReviewAndKeepPriorPublication() {
        review(1);
        publication.publish(workspace, card, 1, 0);
        knowledge.revise(workspace, card, 1, "수정", "21도 관측 [E1]", null);
        assertThat(publication.state(workspace, card).review()).isNull();
        fails(409, () -> publication.publish(workspace, card, 1, 1));
        fails(409, () -> publication.publish(workspace, card, 2, 1));
        assertThat(publication.published(workspace, card).version().number()).isEqualTo(1);
        review(2);
        fails(409, () -> publication.publish(workspace, card, 2, 0));
        assertThat(publication.publish(workspace, card, 2, 1).publication().revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_publication", Integer.class)).isEqualTo(2);
    }

    @Test
    void changedEvidenceBlocksReviewAndPublicationAndRetainsPublishedHistory() {
        review(1);
        publication.publish(workspace, card, 1, 0);
        knowledge.revise(workspace, card, 1, "수정", "21도 [E1]", null);
        review(2);
        jdbc.update("UPDATE extracted_document SET text='가상 온도 22도' WHERE entry_id=?", entry);
        fails(409, () -> publication.publish(workspace, card, 2, 1));
        assertThat(publication.state(workspace, card).publishedEvidence().getFirst().freshness()).isEqualTo("STALE");
        assertThat(publication.published(workspace, card).version().number()).isEqualTo(1);
        knowledge.revise(workspace, card, 2, "수정", "21도 [E1]", null);
        fails(409, () -> review(3));
    }

    @Test
    void missingOriginalBlocksPublishingButKeepsSnapshotAndReview() {
        review(1);
        library.deleteEntry(workspace, entry);
        fails(409, () -> publication.publish(workspace, card, 1, 0));
        assertThat(publication.state(workspace, card).review()).isNotNull();
        assertThat(knowledge.get(workspace, card, null).version().evidence().getFirst().quote()).isEqualTo("가상 온도 21도");
    }

    @Test
    void failedDatabaseWriteLeavesPriorPublicationIntact() {
        review(1);
        publication.publish(workspace, card, 1, 0);
        knowledge.revise(workspace, card, 1, "수정", "21도 [E1]", null);
        review(2);
        jdbc.execute("CREATE TRIGGER fail_publication BEFORE INSERT ON knowledge_publication BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        try {
            assertThatThrownBy(() -> publication.publish(workspace, card, 2, 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(publication.published(workspace, card).version().number()).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER fail_publication"); }
    }

    @Test
    void simultaneousPublishersYieldOneSuccessAndOneConflict() throws Exception {
        review(1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> publish = () -> {
                ready.countDown(); release.await();
                try { publication.publish(workspace, card, 1, 0); return 200; }
                catch (LibraryException exception) { return exception.status(); }
            };
            var first = executor.submit(publish);
            var second = executor.submit(publish);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void restRequiresHeaderAndScopesAllRoutesToWorkspace() throws Exception {
        String base = "/api/workspaces/" + workspace + "/knowledge/" + card;
        String body = "{\"expectedVersion\":1,\"reviewer\":\"가상 검토자\",\"note\":\"대조 완료\",\"claimsSupported\":true,\"uncertaintyRemoved\":true}";
        mvc.perform(post(base + "/review").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/review").header("X-Forge-Client", "1").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.review.version").value(1));
        mvc.perform(post(base + "/publication").header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"expectedVersion\":1,\"expectedPublicationRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publication.version").value(1));
        String other = library.createWorkspace("다른 공간").id();
        String wrong = "/api/workspaces/" + other + "/knowledge/" + card;
        for (String route : List.of("/publication", "/published")) mvc.perform(get(wrong + route)).andExpect(status().isNotFound());
        fails(404, () -> publication.review(other, card, 1, "검토자", "기록", true, true));
        fails(404, () -> publication.publish(other, card, 1, 0));
        mvc.perform(get(base + "/published")).andExpect(status().isOk()).andExpect(jsonPath("$.version.number").value(1));
    }

    @Test
    void deletionCascadesReviewAndPublicationButNotOriginals() {
        review(1);
        publication.publish(workspace, card, 1, 0);
        knowledge.delete(workspace, card);
        for (String table : List.of("knowledge_review", "knowledge_publication")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(library.content(workspace, entry)).isNotEmpty();
    }
}
