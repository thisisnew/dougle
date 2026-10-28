package dev.dougle;

import dev.dougle.attachment.application.AttachmentService;
import dev.dougle.knowledge.application.KnowledgeStore;
import dev.dougle.knowledge.application.PublicationStore;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.search.adapter.JdbcEvidenceSourceStore;
import dev.dougle.search.application.*;
import dev.dougle.search.domain.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamingEvidenceTest {
    @TempDir Path directory;

    @Test void capturesAndValidatesEndOfOneHundredMiBWithoutMaterializingSource() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("fixture.db")));
        jdbc.execute("CREATE TABLE entry(id TEXT,workspace_id TEXT,source_id TEXT,relative_path TEXT,name TEXT,kind TEXT,area TEXT,content BLOB)");
        jdbc.execute("CREATE TABLE source(id TEXT,revision TEXT)");
        jdbc.execute("CREATE TABLE extracted_document(entry_id TEXT,text TEXT,status TEXT)");
        jdbc.execute("CREATE TABLE extracted_text_part(entry_id TEXT,ordinal INTEGER,text TEXT,PRIMARY KEY(entry_id,ordinal))");
        int size = 100 * 1024 * 1024;
        jdbc.update("INSERT INTO entry VALUES('entry','workspace',NULL,NULL,'fixture.txt','FILE','ORIGINAL',zeroblob(?))", size);
        jdbc.update("INSERT INTO extracted_document VALUES('entry','prefix preview','READY')");
        String block = "synthetic filler line\n".repeat(3072);
        int blocks = size / block.length();
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    for (int ordinal = 0; ordinal < blocks; ordinal++) {
                        jdbc.update("INSERT INTO extracted_text_part VALUES('entry',?,?)", ordinal, block);
                    }
                });
        String padding = "x".repeat(size - blocks * block.length()) + "\n";
        String quote = "Final synthetic procedure: close the amber valve.\nConfirm the blue indicator.";
        jdbc.update("INSERT INTO extracted_text_part VALUES('entry',?,?)", blocks, padding + quote.substring(0, 23));
        jdbc.update("INSERT INTO extracted_text_part VALUES('entry',?,?)", blocks + 1, quote.substring(23));
        var sources = new JdbcEvidenceSourceStore(jdbc);
        var material = sources.material("workspace", "entry").orElseThrow();
        var expectedContent = MessageDigest.getInstance("SHA-256");
        byte[] zeros = new byte[64 * 1024];
        for (int offset = 0; offset < size; offset += zeros.length) expectedContent.update(zeros);
        assertThat(material.contentHash()).isEqualTo(HexFormat.of().formatHex(expectedContent.digest()));
        var expectedText = MessageDigest.getInstance("SHA-256");
        for (int ordinal = 0; ordinal < blocks; ordinal++) expectedText.update(block.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        expectedText.update((padding + quote).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = HexFormat.of().formatHex(expectedText.digest());
        assertThat(material.textHash()).isEqualTo(hash);
        int startLine = blocks * 3072 + 2;
        var hit = new SearchIndex.Hit("entry", "fixture.txt", null, "", "fixture.txt", quote, 1,
                startLine, startLine + 1, true, "ORIGINAL", null, null, List.of(), "CURRENT", hash);
        var library = mock(LibraryStore.class);
        var knowledge = mock(KnowledgeStore.class);
        var publications = mock(PublicationStore.class);
        var catalog = mock(SearchCatalog.class);
        var attachments = mock(AttachmentService.class);
        var store = mock(EvidenceBundleStore.class);
        when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "synthetic", "now")));
        when(catalog.document("entry")).thenReturn(Optional.of(new SearchIndex.Document("entry", "workspace", "fixture.txt", "preview", "", null, "", "fixture.txt")));
        when(store.save(eq("workspace"), any())).thenAnswer(call -> new EvidenceBundle("bundle", "workspace", "now", "digest", call.getArgument(1)));
        var writer = new EvidenceBundleWriter(knowledge, publications, library, store, catalog, attachments, sources);
        var bundle = writer.capture("workspace", "What is the final procedure?", List.of(), false, List.of(hit));
        assertThat(bundle.payload().items()).singleElement().satisfies(item -> {
            assertThat(item.quote()).isEqualTo(quote);
            assertThat(item.startLine()).isEqualTo(startLine);
            assertThat(item.endLine()).isEqualTo(startLine + 1);
            assertThat(item.textHash()).isEqualTo(hash);
        });
        var bundles = mock(EvidenceBundleService.class);
        when(bundles.get("workspace", "bundle")).thenReturn(bundle);
        var validator = new EvidenceBundleValidator(bundles, knowledge, publications, catalog, attachments, sources);
        assertThat(validator.check("workspace", "bundle").status()).isEqualTo("CURRENT");
        jdbc.update("UPDATE extracted_text_part SET text='changed outside quote\n' WHERE ordinal=0");
        assertThat(validator.check("workspace", "bundle").items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("TEXT_CHANGED"));
        jdbc.update("UPDATE entry SET content=x'01'");
        assertThat(validator.check("workspace", "bundle").items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("CONTENT_CHANGED"));
        assertThat(bundle.payload().items().getFirst().quote()).isEqualTo(quote);
        assertThat(sources.material("other-workspace", "entry")).isEmpty();
        verifyNoInteractions(knowledge);
    }

    @Test void boundedQuotesVerifyLinesAndCrossPartMatchesIncludingLongSingleLines() {
        var material = material(List.of("prefix\n" + "x".repeat(70000) + "am", "ber\nblue\n"));
        assertThat(material.window("amber\nblue\n", 2, 3, 100, true))
                .isEqualTo(new EvidenceWindow("amber\nblue\n", 2, 3));
        assertThat(material.window("amber", 1, 1, 100, true)).isNull();
        assertThat(material.window("amber", 2, 3, 100, true)).isNull();
        assertThat(material.window("missing", 2, 2, 100, true)).isNull();
        assertThat(material.window("amber", 2, 2, 4, true)).isNull();
        assertThat(material.window("", 2, 2, 100, true)).isNull();
    }

    @Test void legacyTextKeepsHashAndContextWindow() {
        String text = "first\nsecond\nthird";
        var material = new EvidenceMaterial("entry", null, null, null, "fixture", "READY", "hash", text, List.of());
        assertThat(material.textHash()).isEqualTo(ContentDigest.of(text));
        assertThat(material.window("second", 2, 2, 100, false)).isEqualTo(new EvidenceWindow(text, 1, 3));
        assertThat(material.window("second", 2, 2, 100, true)).isEqualTo(new EvidenceWindow("second", 2, 2));
    }

    private EvidenceMaterial material(List<String> parts) {
        return new EvidenceMaterial("entry", null, null, null, "fixture", "READY", "hash", "preview", parts);
    }
}
