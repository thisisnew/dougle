package dev.dougle;

import dev.dougle.knowledge.application.KnowledgeService;
import dev.dougle.knowledge.application.PublicationService;
import dev.dougle.knowledge.domain.Knowledge.Citation;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryService;
import dev.dougle.search.application.RelationSearch;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RelationSearchIntegrationTest {
    @TempDir static Path data;
    @TempDir Path fixture;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", () -> data.toString()); }
    @Autowired KnowledgeService knowledge;
    @Autowired PublicationService publication;
    @Autowired LibraryService library;
    @Autowired SourceService sources;
    @Autowired RelationSearch search;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    String workspace;
    String entry;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("Synthetic relations").id();
        entry = library.upload(workspace, null, "Research.java", "class Research { void observe() {} }".getBytes(StandardCharsets.UTF_8)).id();
    }

    String card(String title, boolean publish) {
        String card = knowledge.create(workspace, title, "Synthetic reference [E1]", List.of(new Citation(entry, 1, 1))).card().id();
        if (publish) {
            publication.review(workspace, card, 1, "Synthetic reviewer", "checked fixture", true, true);
            publication.publish(workspace, card, 1, 0);
        }
        return card;
    }

    @Test
    void originalFindsPublishedCitationsAndExactSymbolsButNeverDraftsOrAnotherWorkspace() throws Exception {
        String published = card("Published", true);
        card("Draft", false);
        String sameSymbol = library.upload(workspace, null, "Other.java", "class Other { void observe() {} }".getBytes(StandardCharsets.UTF_8)).id();
        String other = library.createWorkspace("Other workspace").id();
        library.upload(other, null, "Foreign.java", "class Foreign { void observe() {} }".getBytes(StandardCharsets.UTF_8));
        var found = search.find(workspace, "ORIGINAL", entry, null, List.of());
        assertThat(found.items()).hasSize(2);
        assertThat(found.items()).extracting(item -> item.target().id()).containsExactlyInAnyOrder(published, sameSymbol);
        assertThat(found.items()).filteredOn(item -> item.target().id().equals(sameSymbol)).allSatisfy(item -> {
            assertThat(item.target().relation()).isEqualTo("SAME_SYMBOL");
            assertThat(item.target().detail()).isEqualTo("observe");
        });
        mvc.perform(get("/api/workspaces/" + other + "/relations").param("kind", "ORIGINAL").param("id", entry))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishedCardFindsItsOriginalAndOtherPublicationsSharingOrigin() {
        String first = card("First", true);
        String second = card("Second", true);
        var found = search.find(workspace, "KNOWLEDGE", first, 1, List.of());
        assertThat(found.items()).extracting(item -> item.target().id()).containsExactlyInAnyOrder(entry, second);
        assertThat(found.items()).extracting(item -> item.target().relation()).containsExactlyInAnyOrder("CITED_SOURCE", "SHARED_EVIDENCE");
        assertThatThrownBy(() -> search.find(workspace, "KNOWLEDGE", first, 2, List.of())).isInstanceOf(LibraryException.class);
    }

    @Test
    void deletedOriginalDoesNotEraseSharedHistoricalRelationshipAndShowsMissing() {
        String first = card("First", true);
        String second = card("Second", true);
        library.deleteEntry(workspace, entry);
        var found = search.find(workspace, "KNOWLEDGE", first, 1, List.of());
        assertThat(found.items()).hasSize(1);
        assertThat(found.items().getFirst().target().id()).isEqualTo(second);
        assertThat(found.items().getFirst().freshness()).isEqualTo("MISSING");
    }

    @Test
    void appliesMultipleDomainFilterBeforeLimitAndMarksChangedEvidenceStale() throws Exception {
        Files.writeString(fixture.resolve("Source.java"), "class Source { void observe() {} }");
        var source = sources.create(workspace, "Source", "LOCAL_FOLDER", fixture.toString(), "synthetic-domain");
        sources.sync(workspace, source.id());
        String managed = jdbc.queryForObject("SELECT id FROM entry WHERE source_id=? AND kind='FILE'", String.class, source.id());
        assertThat(search.find(workspace, "ORIGINAL", entry, null, List.of("absent", "synthetic-domain")).items())
                .extracting(item -> item.target().id()).containsExactly(managed);
        assertThat(search.find(workspace, "ORIGINAL", entry, null, List.of("absent")).items()).isEmpty();
        entry = managed;
        String card = card("Managed publication", true);
        Files.writeString(fixture.resolve("Source.java"), "class Source { void changed() {} }");
        sources.sync(workspace, source.id());
        var found = search.find(workspace, "ORIGINAL", managed, null, List.of("synthetic-domain"));
        assertThat(found.items()).hasSize(1);
        assertThat(found.items().getFirst().target().id()).isEqualTo(card);
        assertThat(found.items().getFirst().freshness()).isEqualTo("STALE");
    }

    @Test
    void rejectsDraftSeedAndInvalidInputs() throws Exception {
        String draft = card("Draft", false);
        assertThatThrownBy(() -> search.find(workspace, "KNOWLEDGE", draft, 1, List.of())).isInstanceOf(LibraryException.class);
        mvc.perform(get("/api/workspaces/" + workspace + "/relations").param("kind", "UNKNOWN").param("id", entry))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/workspaces/" + workspace + "/relations").param("kind", "KNOWLEDGE").param("id", draft))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitsResultsAndKeepsOnlyLatestPublishedVersion() {
        for (int index = 0; index < 32; index++) card("Published " + index, true);
        var found = search.find(workspace, "ORIGINAL", entry, null, List.of());
        assertThat(found.items()).hasSize(30);
        assertThat(found.truncated()).isTrue();
        String current = found.items().getFirst().target().id();
        knowledge.revise(workspace, current, 1, "Updated publication", "Updated synthetic [E1]", null);
        publication.review(workspace, current, 2, "Synthetic reviewer", "checked fixture", true, true);
        publication.publish(workspace, current, 2, 1);
        assertThatThrownBy(() -> search.find(workspace, "KNOWLEDGE", current, 1, List.of())).isInstanceOf(LibraryException.class);
        assertThat(search.find(workspace, "ORIGINAL", entry, null, List.of()).items())
                .filteredOn(item -> item.target().id().equals(current))
                .allSatisfy(item -> assertThat(item.target().version()).isEqualTo(2));
    }
}
