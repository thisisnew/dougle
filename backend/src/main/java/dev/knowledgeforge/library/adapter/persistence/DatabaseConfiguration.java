package dev.knowledgeforge.library.adapter.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseConfiguration {
    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        org.springframework.jdbc.support.SQLExceptionSubclassTranslator fallback =
                new org.springframework.jdbc.support.SQLExceptionSubclassTranslator();
        jdbc.setExceptionTranslator((task, sql, exception) -> {
            if (exception instanceof org.sqlite.SQLiteException sqlite
                    && sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
                return new org.springframework.dao.DuplicateKeyException("Duplicate name", exception);
            }
            return fallback.translate(task, sql, exception);
        });
        return jdbc;
    }

    @Bean
    DataSource dataSource(@Value("${forge.data-dir}") String directory) throws IOException {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + root.resolve("knowledge-forge.sqlite"));
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        config.addDataSourceProperty("busy_timeout", "5000");
        HikariDataSource source = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("PRAGMA journal_mode=WAL");
        int version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        if (version > 15) {
            source.close();
            throw new IllegalStateException("Database schema is newer than this application.");
        }
        if (version == 0) {
            org.springframework.transaction.support.TransactionTemplate transaction =
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            transaction.executeWithoutResult(status -> {
                jdbc.execute("""
                        CREATE TABLE workspace (
                          id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                          created_at TEXT NOT NULL
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE app_state (
                          id INTEGER PRIMARY KEY CHECK(id=1),
                          active_workspace_id TEXT REFERENCES workspace(id) ON DELETE SET NULL
                        )
                        """);
                jdbc.update("INSERT INTO app_state(id) VALUES (1)");
                jdbc.execute("""
                        CREATE TABLE entry (
                          id TEXT PRIMARY KEY,
                          workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                          parent_id TEXT REFERENCES entry(id) ON DELETE CASCADE,
                          name TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('FILE','FOLDER')),
                          area TEXT NOT NULL CHECK(area IN ('ORIGINAL','KNOWLEDGE')),
                          size INTEGER NOT NULL, content BLOB NOT NULL, created_at TEXT NOT NULL
                        )
                        """);
                jdbc.execute("""
                        CREATE UNIQUE INDEX entry_sibling_name
                        ON entry(workspace_id, area, COALESCE(parent_id,''), name COLLATE NOCASE)
                        """);
                jdbc.execute("CREATE INDEX entry_parent ON entry(workspace_id, area, parent_id)");
                jdbc.execute("PRAGMA user_version=1");
            });
        }
        if (version < 2) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE source (
                                  id TEXT PRIMARY KEY,
                                  workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  name TEXT NOT NULL, kind TEXT NOT NULL, location TEXT NOT NULL,
                                  domain TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'NEW',
                                  revision TEXT, error TEXT, synced_at TEXT,
                                  root_entry_id TEXT REFERENCES entry(id) ON DELETE SET NULL,
                                  polling_enabled INTEGER NOT NULL DEFAULT 0,
                                  poll_interval_seconds INTEGER NOT NULL DEFAULT 3600,
                                  UNIQUE(workspace_id,name COLLATE NOCASE)
                                )
                                """);
                        jdbc.execute("ALTER TABLE entry ADD COLUMN source_id TEXT REFERENCES source(id) ON DELETE CASCADE");
                        jdbc.execute("ALTER TABLE entry ADD COLUMN relative_path TEXT");
                        jdbc.execute("ALTER TABLE entry ADD COLUMN content_hash TEXT");
                        jdbc.execute("CREATE INDEX entry_source ON entry(source_id)");
                        jdbc.execute("""
                                CREATE TABLE extracted_document (
                                  entry_id TEXT PRIMARY KEY REFERENCES entry(id) ON DELETE CASCADE,
                                  text TEXT NOT NULL, media_type TEXT, status TEXT NOT NULL, error TEXT
                                )
                                """);
                        jdbc.execute("""
                                CREATE TABLE symbol (
                                  entry_id TEXT NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
                                  name TEXT NOT NULL, kind TEXT NOT NULL, line INTEGER NOT NULL, signature TEXT NOT NULL
                                )
                                """);
                        jdbc.execute("CREATE INDEX symbol_entry ON symbol(entry_id)");
                        jdbc.execute("CREATE TABLE search_outbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT, entry_id TEXT NOT NULL)");
                        jdbc.execute("""
                                CREATE TRIGGER entry_search_delete AFTER DELETE ON entry
                                BEGIN INSERT INTO search_outbox(entry_id) VALUES(OLD.id); END
                                """);
                        jdbc.execute("PRAGMA user_version=2");
                    });
        }
        if (version < 3) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE knowledge_card (
                                  id TEXT PRIMARY KEY,
                                  workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  latest_version INTEGER NOT NULL CHECK(latest_version>0), created_at TEXT NOT NULL
                                )
                                """);
                        jdbc.execute("CREATE INDEX knowledge_workspace ON knowledge_card(workspace_id)");
                        jdbc.execute("""
                                CREATE TABLE knowledge_version (
                                  card_id TEXT NOT NULL REFERENCES knowledge_card(id) ON DELETE CASCADE,
                                  number INTEGER NOT NULL CHECK(number>0), title TEXT NOT NULL, markdown TEXT NOT NULL,
                                  status TEXT NOT NULL CHECK(status IN ('DRAFT','VERIFIED','PUBLISHED')),
                                  manually_edited INTEGER NOT NULL, created_at TEXT NOT NULL,
                                  PRIMARY KEY(card_id,number)
                                )
                                """);
                        jdbc.execute("""
                                CREATE TABLE evidence_snapshot (
                                  id TEXT PRIMARY KEY,
                                  workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  origin_key TEXT NOT NULL, entry_id TEXT NOT NULL, source_id TEXT,
                                  source_name TEXT, source_kind TEXT, source_location TEXT,
                                  source_revision TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL,
                                  content_hash TEXT NOT NULL, text_hash TEXT NOT NULL,
                                  content BLOB NOT NULL, extracted_text TEXT NOT NULL, captured_at TEXT NOT NULL,
                                  UNIQUE(workspace_id,origin_key,source_revision,content_hash,text_hash)
                                )
                                """);
                        jdbc.execute("""
                                CREATE TABLE knowledge_evidence (
                                  card_id TEXT NOT NULL, version INTEGER NOT NULL, ordinal INTEGER NOT NULL,
                                  snapshot_id TEXT NOT NULL REFERENCES evidence_snapshot(id),
                                  start_line INTEGER NOT NULL CHECK(start_line>0),
                                  end_line INTEGER NOT NULL CHECK(end_line>=start_line),
                                  PRIMARY KEY(card_id,version,ordinal),
                                  FOREIGN KEY(card_id,version) REFERENCES knowledge_version(card_id,number) ON DELETE CASCADE
                                )
                                """);
                        jdbc.execute("CREATE INDEX knowledge_evidence_snapshot ON knowledge_evidence(snapshot_id)");
                        jdbc.execute("PRAGMA user_version=3");
                    });
        }
        if (version < 4) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE generation_job (
                                  id TEXT NOT NULL, workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  source_card_id TEXT NOT NULL, provider TEXT NOT NULL,
                                  expected_version INTEGER NOT NULL, digest TEXT NOT NULL,
                                  status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','CANCELLING','CANCELLED','SUCCEEDED','FAILED','INTERRUPTED')),
                                  result_card_id TEXT, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                                  PRIMARY KEY(workspace_id,id)
                                )
                                """);
                        jdbc.execute("CREATE INDEX generation_job_recent ON generation_job(workspace_id,created_at DESC)");
                        jdbc.execute("PRAGMA user_version=4");
                    });
        }
        if (version < 5) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("ALTER TABLE generation_job ADD COLUMN requested_model TEXT");
                        jdbc.execute("PRAGMA user_version=5");
                    });
        }
        if (version < 6) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("ALTER TABLE generation_job ADD COLUMN cli_version TEXT");
                        jdbc.execute("ALTER TABLE generation_job ADD COLUMN cli_version_status TEXT NOT NULL DEFAULT 'NOT_CHECKED' CHECK(cli_version_status IN ('NOT_CHECKED','AVAILABLE','UNAVAILABLE','UNRECOGNIZED','BUSY'))");
                        jdbc.execute("ALTER TABLE generation_job ADD COLUMN cli_checked_at TEXT");
                        jdbc.execute("PRAGMA user_version=6");
                    });
        }
        if (version < 7) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE knowledge_review (
                                  card_id TEXT NOT NULL, version INTEGER NOT NULL,
                                  reviewer TEXT NOT NULL, note TEXT NOT NULL, reviewed_at TEXT NOT NULL,
                                  PRIMARY KEY(card_id,version),
                                  FOREIGN KEY(card_id,version) REFERENCES knowledge_version(card_id,number) ON DELETE CASCADE
                                )
                                """);
                        jdbc.execute("""
                                CREATE TABLE knowledge_publication (
                                  card_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
                                  version INTEGER NOT NULL, published_at TEXT NOT NULL,
                                  PRIMARY KEY(card_id,revision), UNIQUE(card_id,version),
                                  FOREIGN KEY(card_id,version) REFERENCES knowledge_review(card_id,version) ON DELETE CASCADE
                                )
                                """);
                        jdbc.execute("PRAGMA user_version=7");
                    });
        }
        if (version < 8) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE knowledge_folder (
                                  id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  parent_id TEXT REFERENCES knowledge_folder(id) ON DELETE CASCADE, name TEXT NOT NULL
                                )
                                """);
                        jdbc.execute("CREATE UNIQUE INDEX knowledge_folder_name ON knowledge_folder(workspace_id,COALESCE(parent_id,''),name)");
                        jdbc.execute("""
                                CREATE TABLE knowledge_placement (
                                  card_id TEXT PRIMARY KEY REFERENCES knowledge_card(id) ON DELETE CASCADE,
                                  folder_id TEXT REFERENCES knowledge_folder(id) ON DELETE SET NULL,
                                  revision INTEGER NOT NULL CHECK(revision>0)
                                )
                                """);
                        jdbc.execute("CREATE INDEX knowledge_placement_folder ON knowledge_placement(folder_id)");
                        jdbc.execute("PRAGMA user_version=8");
                    });
        }
        if (version < 9) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("ALTER TABLE knowledge_folder ADD COLUMN revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0)");
                        jdbc.execute("PRAGMA user_version=9");
                    });
        }
        if (version < 10) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE proposal_decision (
                                  workspace_id TEXT NOT NULL, job_id TEXT NOT NULL,
                                  revision INTEGER NOT NULL CHECK(revision>0),
                                  status TEXT NOT NULL CHECK(status IN ('OPEN','REJECTED')),
                                  source_version INTEGER NOT NULL CHECK(source_version>0),
                                  result_version INTEGER NOT NULL CHECK(result_version>0),
                                  note TEXT NOT NULL, created_at TEXT NOT NULL,
                                  PRIMARY KEY(workspace_id,job_id,revision),
                                  FOREIGN KEY(workspace_id,job_id) REFERENCES generation_job(workspace_id,id) ON DELETE CASCADE
                                )
                                """);
                        jdbc.execute("PRAGMA user_version=10");
                    });
        }
        if (version < 11) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE proposal_acceptance (
                                  workspace_id TEXT NOT NULL, job_id TEXT NOT NULL,
                                  source_version INTEGER NOT NULL CHECK(source_version>0),
                                  result_version INTEGER NOT NULL CHECK(result_version=1),
                                  applied_version INTEGER NOT NULL CHECK(applied_version=source_version+1),
                                  decision_revision INTEGER NOT NULL CHECK(decision_revision>=0),
                                  note TEXT NOT NULL, accepted_at TEXT NOT NULL,
                                  PRIMARY KEY(workspace_id,job_id),
                                  FOREIGN KEY(workspace_id,job_id) REFERENCES generation_job(workspace_id,id) ON DELETE CASCADE
                                )
                                """);
                        jdbc.execute("PRAGMA user_version=11");
                    });
        }
        if (version < 12) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("ALTER TABLE proposal_acceptance ADD COLUMN mode TEXT NOT NULL DEFAULT 'FULL' CHECK(mode IN ('FULL','MANUAL'))");
                        jdbc.execute("PRAGMA user_version=12");
                    });
        }
        if (version < 13) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TRIGGER publication_search_insert AFTER INSERT ON knowledge_publication
                                BEGIN INSERT INTO search_outbox(entry_id) VALUES('knowledge:' || NEW.card_id); END
                                """);
                        jdbc.execute("""
                                CREATE TRIGGER knowledge_search_delete AFTER DELETE ON knowledge_card
                                BEGIN INSERT INTO search_outbox(entry_id) VALUES('knowledge:' || OLD.id); END
                                """);
                        jdbc.execute("""
                                CREATE TRIGGER source_knowledge_search_delete AFTER DELETE ON source
                                BEGIN INSERT INTO search_outbox(entry_id)
                                  SELECT DISTINCT 'knowledge:' || e.card_id FROM knowledge_evidence e
                                  JOIN evidence_snapshot snapshot ON snapshot.id=e.snapshot_id WHERE snapshot.source_id=OLD.id;
                                END
                                """);
                        jdbc.execute("""
                                CREATE TRIGGER source_knowledge_search_domain AFTER UPDATE OF domain ON source
                                BEGIN INSERT INTO search_outbox(entry_id)
                                  SELECT DISTINCT 'knowledge:' || e.card_id FROM knowledge_evidence e
                                  JOIN evidence_snapshot snapshot ON snapshot.id=e.snapshot_id WHERE snapshot.source_id=NEW.id;
                                END
                                """);
                        jdbc.execute("INSERT INTO search_outbox(entry_id) SELECT DISTINCT 'knowledge:' || card_id FROM knowledge_publication");
                        jdbc.execute("PRAGMA user_version=13");
                    });
        }
        if (version < 14) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE evidence_bundle (
                                  id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
                                  question TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('READY','NO_EVIDENCE')),
                                  used_characters INTEGER NOT NULL CHECK(used_characters BETWEEN 0 AND 12000),
                                  payload_json TEXT NOT NULL, digest TEXT NOT NULL, created_at TEXT NOT NULL
                                )
                                """);
                        jdbc.execute("CREATE INDEX evidence_bundle_workspace ON evidence_bundle(workspace_id,created_at)");
                        jdbc.execute("PRAGMA user_version=14");
                    });
        }
        if (version < 15) {
            new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source))
                    .executeWithoutResult(status -> {
                        jdbc.execute("""
                                CREATE TABLE answer_approval (
                                  bundle_id TEXT PRIMARY KEY REFERENCES evidence_bundle(id) ON DELETE CASCADE,
                                  id TEXT NOT NULL UNIQUE, provider TEXT NOT NULL CHECK(provider IN ('CODEX','CLAUDE')),
                                  requested_model TEXT NOT NULL, digest TEXT NOT NULL,
                                  approved_at TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT
                                )
                                """);
                        jdbc.execute("PRAGMA user_version=15");
                    });
        }
        jdbc.update("UPDATE generation_job SET status='INTERRUPTED',error='앱 재시작으로 이전 작업이 중단되었습니다. 새 요청은 다시 승인하세요.',updated_at=? WHERE status IN ('QUEUED','RUNNING','CANCELLING')", java.time.Instant.now().toString());
        jdbc.update("UPDATE source SET status='ERROR',error='이전 동기화가 중단되었습니다. 다시 동기화하세요.' WHERE status='SYNCING'");
        return source;
    }
}
