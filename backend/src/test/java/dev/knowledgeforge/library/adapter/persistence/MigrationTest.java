package dev.knowledgeforge.library.adapter.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class MigrationTest {
    @TempDir Path directory;

    @Test
    void migratesVersionOneWithoutLosingWorkspaceSelectionOrOriginalBytes() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("knowledge-forge.sqlite"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workspace(id TEXT PRIMARY KEY,name TEXT,created_at TEXT)");
            statement.execute("CREATE TABLE app_state(id INTEGER PRIMARY KEY,active_workspace_id TEXT)");
            statement.execute("CREATE TABLE entry(id TEXT PRIMARY KEY,workspace_id TEXT,parent_id TEXT,name TEXT,kind TEXT,area TEXT,size INTEGER,content BLOB,created_at TEXT)");
            statement.execute("INSERT INTO workspace VALUES('one','existing','now')");
            statement.execute("INSERT INTO app_state VALUES(1,'one')");
            statement.execute("INSERT INTO entry VALUES('file','one',NULL,'old.txt','FILE','ORIGINAL',3,X'616263','now')");
            statement.execute("PRAGMA user_version=1");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
                assertThat(jdbc.queryForObject("SELECT active_workspace_id FROM app_state", String.class)).isEqualTo("one");
                assertThat(jdbc.queryForObject("SELECT content FROM entry", byte[].class)).isEqualTo(new byte[] {97, 98, 99});
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source", Integer.class)).isZero();
            }
        }
    }

    @Test
    void migratesVersionTwoAndReopensWithoutLosingSourceAnalysis() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("UPDATE app_state SET active_workspace_id='one'");
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,revision) VALUES('source','one','fixture','LOCAL_FOLDER','/synthetic','rev1')");
            jdbc.update("INSERT INTO entry(id,workspace_id,name,kind,area,size,content,created_at,source_id,relative_path) VALUES('file','one','notes.txt','FILE','ORIGINAL',3,X'616263','now','source','notes.txt')");
            jdbc.update("INSERT INTO extracted_document(entry_id,text,status) VALUES('file','abc','READY')");
            jdbc.execute("DROP TABLE generation_job");
            jdbc.execute("DROP TABLE knowledge_evidence");
            jdbc.execute("DROP TABLE evidence_snapshot");
            jdbc.execute("DROP TABLE knowledge_version");
            jdbc.execute("DROP TABLE knowledge_card");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("PRAGMA user_version=2");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
                assertThat(jdbc.queryForObject("SELECT revision FROM source", String.class)).isEqualTo("rev1");
                assertThat(jdbc.queryForObject("SELECT text FROM extracted_document", String.class)).isEqualTo("abc");
                assertThat(jdbc.queryForObject("SELECT content FROM entry", byte[].class)).isEqualTo(new byte[] {97, 98, 99});
                assertThat(jdbc.queryForObject("SELECT active_workspace_id FROM app_state", String.class)).isEqualTo("one");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card", Integer.class)).isZero();
            }
        }
    }

    @Test
    void migratesVersionThreePreservingAiAndHumanVersions() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',2,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'AI','unverified','DRAFT',0,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',2,'Human','edited','DRAFT',1,'now')");
            jdbc.execute("DROP TABLE generation_job");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("PRAGMA user_version=3");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForList("SELECT manually_edited FROM knowledge_version ORDER BY number", Integer.class)).containsExactly(0, 1);
            assertThat(jdbc.queryForList("SELECT markdown FROM knowledge_version ORDER BY number", String.class)).containsExactly("unverified", "edited");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isZero();
        }
    }

    @Test
    void migratesVersionFourWithoutInventingHistoricalModelOrLosingJobs() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO generation_job VALUES('done','one','card','CODEX',1,'digest','SUCCEEDED','result',NULL,'old','old',NULL,NULL,'NOT_CHECKED',NULL)");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version_status");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_checked_at");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN requested_model");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("PRAGMA user_version=4");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
                assertThat(jdbc.queryForObject("SELECT requested_model FROM generation_job", String.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT result_card_id FROM generation_job", String.class)).isEqualTo("result");
                assertThat(jdbc.queryForObject("SELECT digest FROM generation_job", String.class)).isEqualTo("digest");
                assertThat(jdbc.queryForObject("SELECT status FROM generation_job", String.class)).isEqualTo("SUCCEEDED");
            }
        }
    }

    @Test
    void migratesVersionFiveWithoutInventingPastCliVersions() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO generation_job VALUES('done','one','card','CODEX',1,'digest','SUCCEEDED','result',NULL,'old','old','selected',NULL,'NOT_CHECKED',NULL)");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version_status");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_checked_at");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("PRAGMA user_version=5");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
                assertThat(jdbc.queryForObject("SELECT cli_version FROM generation_job", String.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT cli_checked_at FROM generation_job", String.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT cli_version_status FROM generation_job", String.class)).isEqualTo("NOT_CHECKED");
                assertThat(jdbc.queryForObject("SELECT requested_model FROM generation_job", String.class)).isEqualTo("selected");
                assertThat(jdbc.queryForObject("SELECT result_card_id FROM generation_job", String.class)).isEqualTo("result");
            }
        }
    }

    @Test
    void migratesVersionSixWithoutInventingReviewsAndPersistsPublicationOnReopen() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',1,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'Human','edited','DRAFT',1,'now')");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("PRAGMA user_version=6");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT markdown FROM knowledge_version", String.class)).isEqualTo("edited");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_review", Integer.class)).isZero();
            jdbc.update("INSERT INTO knowledge_review VALUES('card',1,'synthetic','checked','now')");
            jdbc.update("INSERT INTO knowledge_publication VALUES('card',1,1,'now')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT note FROM knowledge_review", String.class)).isEqualTo("checked");
        }
    }

    @Test
    void migratesVersionSevenAndPersistsIndependentFolders() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',1,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'Human','edited','DRAFT',1,'now')");
            jdbc.update("INSERT INTO knowledge_review VALUES('card',1,'synthetic','checked','now')");
            jdbc.update("INSERT INTO knowledge_publication VALUES('card',1,1,'now')");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("PRAGMA user_version=7");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_placement", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT markdown FROM knowledge_version", String.class)).isEqualTo("edited");
            assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
            jdbc.update("INSERT INTO knowledge_folder VALUES('folder','one',NULL,'synthetic')");
            jdbc.update("INSERT INTO knowledge_placement VALUES('card','folder',1)");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT folder_id FROM knowledge_placement", String.class)).isEqualTo("folder");
            assertThat(jdbc.queryForObject("SELECT note FROM knowledge_review", String.class)).isEqualTo("checked");
        }
    }

    @Test
    void restartInterruptsUnfinishedJobsButKeepsCompletedResultsAndIdempotencyKeys() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            for (String state : java.util.List.of("QUEUED", "RUNNING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED")) {
                jdbc.update("INSERT INTO generation_job VALUES(?,'one','card','CODEX',1,'digest',?,'result',NULL,'old','old','synthetic-model','0.1.0','AVAILABLE','observed')", state, state);
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job WHERE status='INTERRUPTED'", Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT status FROM generation_job WHERE id='SUCCEEDED'", String.class)).isEqualTo("SUCCEEDED");
                assertThat(jdbc.queryForObject("SELECT result_card_id FROM generation_job WHERE id='SUCCEEDED'", String.class)).isEqualTo("result");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(6);
                assertThat(jdbc.queryForList("SELECT requested_model FROM generation_job", String.class)).containsOnly("synthetic-model");
                assertThat(jdbc.queryForList("SELECT cli_version FROM generation_job", String.class)).containsOnly("0.1.0");
                assertThat(jdbc.queryForList("SELECT cli_checked_at FROM generation_job", String.class)).containsOnly("observed");
            }
        }
    }
}
