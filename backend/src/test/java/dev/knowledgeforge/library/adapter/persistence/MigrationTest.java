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
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(3);
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
            jdbc.execute("DROP TABLE knowledge_evidence");
            jdbc.execute("DROP TABLE evidence_snapshot");
            jdbc.execute("DROP TABLE knowledge_version");
            jdbc.execute("DROP TABLE knowledge_card");
            jdbc.execute("PRAGMA user_version=2");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT revision FROM source", String.class)).isEqualTo("rev1");
                assertThat(jdbc.queryForObject("SELECT text FROM extracted_document", String.class)).isEqualTo("abc");
                assertThat(jdbc.queryForObject("SELECT content FROM entry", byte[].class)).isEqualTo(new byte[] {97, 98, 99});
                assertThat(jdbc.queryForObject("SELECT active_workspace_id FROM app_state", String.class)).isEqualTo("one");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_card", Integer.class)).isZero();
            }
        }
    }
}
