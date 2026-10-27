package dev.knowledgeforge.library.adapter.persistence;

import com.zaxxer.hikari.HikariDataSource;
import dev.knowledgeforge.ingestion.adapter.JdbcAnalysisStore;
import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.search.adapter.JdbcSearchCatalog;
import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.sources.adapter.JdbcSourceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class LargeFileIngestionTest {
    @TempDir Path directory;
    private static final int MIB = 1024 * 1024;
    private static final List<String> MARKERS = List.of("amberfrontsignal", "violetmiddlesignal", "cobalttailsignal");

    @Test
    void chunksSurviveStorageCheckpointRestartCompetitionAndReplacement() throws Exception {
        exercise(4, new int[] {1, 2, 3});
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.acceptance.large", matches = "true")
    void hundredMiBRetainsMarkersAtOneFiftyAndNinetyNineMiB() throws Exception {
        exercise(100, new int[] {1, 50, 99});
    }

    private void exercise(int sizeMiB, int[] positions) throws Exception {
        Path fixture = directory.resolve("synthetic.txt");
        byte[] block = new byte[64 * 1024];
        java.util.Arrays.fill(block, (byte) ' ');
        for (int offset = 127; offset < block.length; offset += 128) block[offset] = '\n';
        try (var output = Files.newOutputStream(fixture)) {
            for (int count = 0; count < sizeMiB * 16; count++) output.write(block);
        }
        try (var file = new java.io.RandomAccessFile(fixture.toFile(), "rw")) {
            for (int index = 0; index < positions.length; index++) {
                file.seek((long) positions[index] * MIB);
                file.write((MARKERS.get(index) + " synthetic evidence\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        String digest;
        int count;
        try (var source = database()) {
            var jdbc = new JdbcTemplate(source);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            var analyses = new JdbcAnalysisStore(jdbc);
            jdbc.update("INSERT INTO workspace VALUES('workspace','Synthetic large file','now')");
            jdbc.update("INSERT INTO entry(id,workspace_id,name,kind,area,size,content,created_at) VALUES('entry','workspace','synthetic.txt','FILE','ORIGINAL',?,?,'now')",
                    Files.size(fixture), new byte[0]);
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location) VALUES('source','workspace','Synthetic checkpoint','FOLDER','synthetic')");
            var checkpoints = new JdbcSourceStore(jdbc, analyses, Clock.systemUTC());
            try (var input = Files.newInputStream(fixture); var analysis = new TikaJavaAnalyzer().analyzeStream("synthetic.txt", input)) {
                assertThat(analysis.status()).isEqualTo("READY");
                assertThat(analysis.text().length()).isLessThanOrEqualTo(64 * 1024);
                assertThat(analysis.parts()).isNotEmpty();
                digest = ContentDigest.ofParts(analysis.textParts());
                count = analysis.parts().size();
                for (String marker : MARKERS) assertThat(analysis.textParts().stream().anyMatch(part -> part.contains(marker))).isTrue();
                transaction.executeWithoutResult(status -> checkpoints.checkpoint("source", "synthetic.txt", "fixture-hash", analysis));
            }
            var checkpoint = checkpoints.checkpoint("source", "synthetic.txt", "fixture-hash").orElseThrow();
            assertThat(checkpoint.parts()).hasSize(count);
            transaction.executeWithoutResult(status -> analyses.save("entry", checkpoint));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extracted_text_part", Integer.class)).isEqualTo(count);
            assertThat(jdbc.queryForObject("SELECT MAX(length(text)) FROM extracted_text_part", Integer.class)).isLessThanOrEqualTo(64 * 1024);
            assertThat(jdbc.queryForObject("SELECT length(analysis_json) FROM source_analysis_checkpoint", Integer.class)).isLessThan(100_000);
        }
        try (var source = database(); var index = new LuceneSearchIndex(directory.resolve("search").toString())) {
            var jdbc = new JdbcTemplate(source);
            var catalog = new JdbcSearchCatalog(jdbc);
            var analyses = new JdbcAnalysisStore(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.executeWithoutResult(status -> {
                var document = catalog.document("entry").orElseThrow();
                assertThat(document.textHash()).isEqualTo(digest);
                assertThat(analyses.find("workspace", "entry").orElseThrow().parts()).hasSize(count);
                index.put(document);
                index.put(document);
            });
            for (int competitor = 0; competitor < 50; competitor++) {
                index.put(new SearchIndex.Document("other-" + competitor, "workspace", "unrelated.txt",
                        "unrelated synthetic astronomy observations", "", null, "", "unrelated.txt"));
            }
            index.commit();
            verify(index, digest);
        }
        try (var source = database(); var index = new LuceneSearchIndex(directory.resolve("search").toString())) {
            assertThat(index.initialized()).isTrue();
            verify(index, digest);
            var jdbc = new JdbcTemplate(source);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            var analyses = new JdbcAnalysisStore(jdbc);
            transaction.executeWithoutResult(status -> analyses.save("entry", new Analysis("replacement evidence", "text/plain", "READY", null, List.of())));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extracted_text_part", Integer.class)).isZero();
            transaction.executeWithoutResult(status ->
                    index.put(new JdbcSearchCatalog(jdbc).document("entry").orElseThrow()));
            index.commit();
            for (String marker : MARKERS) assertThat(index.search("workspace", marker, List.of())).isEmpty();
            jdbc.update("DELETE FROM workspace WHERE id='workspace'");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_analysis_text_part", Integer.class)).isZero();
        }
    }

    private void verify(LuceneSearchIndex index, String digest) {
        for (String marker : MARKERS) {
            var hits = index.search("workspace", marker, List.of());
            assertThat(hits).hasSize(1);
            assertThat(hits.getFirst().entryId()).isEqualTo("entry");
            assertThat(hits.getFirst().snippet()).contains(marker);
            assertThat(hits.getFirst().textHash()).isEqualTo(digest);
            assertThat(hits.getFirst().startLine()).isGreaterThan(1);
            assertThat(index.search("other-workspace", marker, List.of())).isEmpty();
        }
    }

    private HikariDataSource database() throws Exception {
        return (HikariDataSource) new DatabaseConfiguration().dataSource(directory.resolve("database").toString());
    }
}
