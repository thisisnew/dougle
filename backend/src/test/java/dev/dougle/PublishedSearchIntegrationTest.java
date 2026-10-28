package dev.dougle;

import dev.dougle.knowledge.application.KnowledgeService;
import dev.dougle.knowledge.application.PublicationService;
import dev.dougle.knowledge.domain.Knowledge.Citation;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.application.SourceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PublishedSearchIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-published-search-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired KnowledgeService knowledge;
    @Autowired PublicationService publication;
    @Autowired LibraryService library;
    @Autowired SourceService sources;
    @Autowired SearchService search;
    @Autowired SearchIndex index;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path fixture;
    String workspace;
    String entry;
    String card;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM workspace");
        search.refresh();
        workspace = library.createWorkspace("synthetic search").id();
        entry = library.upload(workspace, null, "note.txt", "telescope observation".getBytes(StandardCharsets.UTF_8)).id();
        card = knowledge.create(workspace, "telescope guide", "telescope publication [E1]",
                List.of(new Citation(entry, 1, 1))).card().id();
        search.refresh();
    }

    void publish(int version, int revision) {
        publication.review(workspace, card, version, "synthetic reviewer", "checked fixture", true, true);
        publication.publish(workspace, card, version, revision);
        search.refresh();
    }

    List<SearchIndex.Hit> find(String query) { return search.search(workspace, query, List.of(), true); }

    @Test
    void includesOnlyExplicitlyPublishedVersionAndRetainsOriginalOnlyDefault() throws Exception {
        assertThat(find("telescope")).hasSize(1).allMatch(hit -> hit.kind().equals("ORIGINAL"));
        publication.review(workspace, card, 1, "reviewer", "checked", true, true);
        search.refresh();
        assertThat(find("publication")).isEmpty();
        publication.publish(workspace, card, 1, 0);
        search.refresh();
        assertThat(find("telescope")).hasSize(2);
        assertThat(search.search(workspace, "telescope", List.of())).hasSize(1);
        var hit = find("publication").getFirst();
        assertThat(hit.kind()).isEqualTo("KNOWLEDGE");
        assertThat(hit.entryId()).isNull();
        assertThat(hit.cardId()).isEqualTo(card);
        assertThat(hit.version()).isEqualTo(1);
        assertThat(hit.freshness()).isEqualTo("CURRENT");
        mvc.perform(get("/api/workspaces/{workspace}/search", workspace).param("query", "publication"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/workspaces/{workspace}/search", workspace).param("query", "publication").param("includeKnowledge", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].cardId").value(card))
                .andExpect(jsonPath("$[0].version").value(1)).andExpect(jsonPath("$[0].startLine").value(1));
    }

    @Test
    void humanDraftCannotReplacePublicationAndRepublishingReplacesIndex() {
        publish(1, 0);
        knowledge.revise(workspace, card, 1, "private heading", "unpublished secret [E1]", null);
        search.refresh();
        assertThat(find("publication")).hasSize(1);
        assertThat(find("secret")).isEmpty();
        publish(2, 1);
        assertThat(find("publication")).isEmpty();
        assertThat(find("secret")).hasSize(1).allMatch(hit -> hit.version() == 2);
        search.rebuild();
        assertThat(find("secret")).hasSize(1);
        assertThat(knowledge.get(workspace, card, 1).version().markdown()).contains("publication");
    }

    @Test
    void detectsChangedAndDeletedEvidenceWithoutReindexOrLlm() {
        publish(1, 0);
        jdbc.update("UPDATE entry SET content=? WHERE id=?", "changed".getBytes(StandardCharsets.UTF_8), entry);
        assertThat(find("publication").getFirst().freshness()).isEqualTo("STALE");
        library.deleteEntry(workspace, entry);
        assertThat(find("publication").getFirst().freshness()).isEqualTo("MISSING");
        assertThat(knowledge.get(workspace, card, 1).version().markdown()).contains("publication");
    }

    @Test
    void excludesDeletedCardsAndOtherWorkspacesEvenBeforeQueueDrain() {
        publish(1, 0);
        String other = library.createWorkspace("other").id();
        assertThat(search.search(other, "publication", List.of(), true)).isEmpty();
        knowledge.delete(workspace, card);
        assertThat(find("publication")).isEmpty();
        search.refresh();
        assertThat(find("publication")).isEmpty();
    }

    @Test
    void suppressesSupersededIndexedVersionUntilNewPublicationIsIndexed() {
        publish(1, 0);
        knowledge.revise(workspace, card, 1, "new title", "replacement [E1]", null);
        publish(2, 1);
        index.put(new SearchIndex.Document("knowledge:" + card, workspace, "outdated title", "publication",
                "", "", "", "", "KNOWLEDGE", card, 1, List.of()));
        index.commit();
        assertThat(find("publication")).isEmpty();
        search.rebuild();
        assertThat(find("replacement")).hasSize(1).allMatch(hit -> hit.version() == 2);
    }

    @Test
    void rolledBackPublicationDoesNotLeakIntoIndex() {
        publication.review(workspace, card, 1, "reviewer", "checked", true, true);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publication.publish(workspace, card, 1, 0);
            status.setRollbackOnly();
        });
        search.refresh();
        assertThat(find("publication")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE entry_id=?", Integer.class, "knowledge:" + card)).isZero();
    }

    @Test
    void usesAllEvidenceSourceDomainsAndRemovesDeletedSourceDomain() throws Exception {
        Files.writeString(fixture.resolve("notes.txt"), "synthetic reference");
        var first = sources.create(workspace, "first", "LOCAL_FOLDER", fixture.toString(), "research");
        var second = sources.create(workspace, "second", "LOCAL_FOLDER", fixture.toString(), "literature");
        sources.sync(workspace, first.id());
        sources.sync(workspace, second.id());
        var entries = jdbc.queryForList("SELECT id FROM entry WHERE workspace_id=? AND source_id IS NOT NULL AND kind='FILE' ORDER BY id", String.class, workspace);
        card = knowledge.create(workspace, "cross domain", "combined findings [E1] [E2]",
                entries.stream().map(id -> new Citation(id, 1, 1)).toList()).card().id();
        publish(1, 0);
        var hits = search.search(workspace, "combined", List.of("research", "literature"), true);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().domains()).containsExactly("literature", "research");
        assertThat(search.search(workspace, "combined", List.of("research"), true)).hasSize(1);
        assertThat(search.search(workspace, "combined", List.of("other"), true)).isEmpty();
        sources.delete(workspace, first.id());
        search.refresh();
        assertThat(search.search(workspace, "combined", List.of("research"), true)).isEmpty();
        assertThat(search.search(workspace, "combined", List.of("literature"), true).getFirst().freshness()).isEqualTo("MISSING");
        jdbc.update("UPDATE source SET domain='renamed' WHERE id=?", second.id());
        search.refresh();
        assertThat(search.search(workspace, "combined", List.of("literature"), true)).isEmpty();
        assertThat(search.search(workspace, "combined", List.of("renamed"), true)).hasSize(1);
    }
}
