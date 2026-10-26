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
    void migratesTwentyEightPreservingOldPlansAndDurableContinuationClaims() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("DROP TABLE investigation_capture");
            jdbc.execute("DROP TABLE investigation_continuation");
            jdbc.execute("ALTER TABLE investigation_plan DROP COLUMN continuation_root");
            jdbc.execute("ALTER TABLE investigation_plan DROP COLUMN capture_job_id");
            jdbc.execute("PRAGMA user_version=28");
            jdbc.update("INSERT INTO workspace VALUES('continuation-workspace','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle(id,workspace_id,question,status,used_characters,payload_json,digest,created_at) VALUES('root','continuation-workspace','synthetic','NO_EVIDENCE',0,'{}','unchanged','now')");
            jdbc.update("""
                    INSERT INTO investigation_plan(digest,workspace_id,bundle_id,provider,requested_model,binding_digest,prompt,created_at,expires_at,updated_at,status,request_id,approved_at)
                    VALUES('old-digest','continuation-workspace','root','CODEX','synthetic','binding','old prompt','now','later','now','SUCCEEDED','old-request','now')
                    """);
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT prompt FROM investigation_plan WHERE digest='old-digest'", String.class)).isEqualTo("old prompt");
            assertThat(jdbc.queryForObject("SELECT continuation_root FROM investigation_plan WHERE digest='old-digest'", String.class)).isNull();
            jdbc.update("INSERT INTO investigation_continuation VALUES('continuation-workspace','root','approved-request')");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO investigation_continuation VALUES('continuation-workspace','root','another-request')"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            jdbc.update("DELETE FROM investigation_plan WHERE digest='old-digest'");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT request_id FROM investigation_continuation", String.class)).isEqualTo("approved-request");
            assertThat(jdbc.queryForObject("SELECT digest FROM evidence_bundle WHERE id='root'", String.class)).isEqualTo("unchanged");
            jdbc.update("DELETE FROM workspace WHERE id='continuation-workspace'");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM investigation_continuation", Integer.class)).isZero();
        }
    }

    @Test
    void migratesTwentySevenAndPreservesReplyReceiptsAcrossRestartWithoutResending() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('reply-workspace','reply fixture','now')");
            jdbc.update("INSERT INTO evidence_bundle(id,workspace_id,question,status,used_characters,payload_json,digest,created_at) VALUES('old-bundle','reply-workspace','question','NO_EVIDENCE',0,'{}','preserved','now')");
            jdbc.execute("DROP TABLE slack_reply");
            jdbc.execute("PRAGMA user_version=27");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT digest FROM evidence_bundle WHERE id='old-bundle'", String.class)).isEqualTo("preserved");
            for (String status : java.util.List.of("SENDING", "SENT", "CANCELLED", "PREVIEW")) {
                jdbc.update("""
                        INSERT INTO slack_reply(id,workspace_id,inbox_item_id,preview_request_id,scope_hash,scope_json,body,digest,
                          status,execute_request_id,dispatch_count,created_at,expires_at,updated_at)
                        VALUES(?,'reply-workspace','item',?,'scope','{}','Synthetic reply','digest',?,?,?,'now','later','now')
                        """, status, "preview-" + status, status, "execute-" + status, java.util.Set.of("SENDING", "SENT").contains(status) ? 1 : 0);
            }
            assertThatThrownBy(() -> jdbc.update("UPDATE slack_reply SET dispatch_count=2 WHERE id='SENT'"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE slack_reply SET preview_request_id='preview-SENT' WHERE id='PREVIEW'"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT status FROM slack_reply WHERE id='SENDING'", String.class)).isEqualTo("UNCERTAIN");
            assertThat(jdbc.queryForObject("SELECT failure_code FROM slack_reply WHERE id='SENDING'", String.class)).isEqualTo("RESTART");
            assertThat(jdbc.queryForObject("SELECT dispatch_count FROM slack_reply WHERE id='SENDING'", Integer.class)).isEqualTo(1);
            for (String status : java.util.List.of("SENT", "CANCELLED", "PREVIEW")) {
                assertThat(jdbc.queryForObject("SELECT status FROM slack_reply WHERE id=?", String.class, status)).isEqualTo(status);
            }
        }
    }

    @Test
    void workspaceDeletionWaitsUntilSlackReplyDispatchIsResolved() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('reply-workspace','reply fixture','now')");
            jdbc.update("""
                    INSERT INTO slack_reply(id,workspace_id,inbox_item_id,preview_request_id,scope_hash,scope_json,body,digest,
                      status,execute_request_id,dispatch_count,created_at,expires_at,updated_at)
                    VALUES('reply','reply-workspace','item','preview','scope','{}','Synthetic reply','digest',
                      'SENDING','execute',1,'now','later','now')
                    """);
            var store = new SqliteLibraryStore(jdbc);
            assertThatThrownBy(() -> store.deleteWorkspace("reply-workspace"))
                    .isInstanceOfSatisfying(dev.knowledgeforge.library.application.LibraryException.class, error -> assertThat(error.status()).isEqualTo(409));
            assertThat(store.workspace("reply-workspace")).isPresent();
            jdbc.update("UPDATE slack_reply SET status='UNCERTAIN' WHERE id='reply'");
            store.deleteWorkspace("reply-workspace");
            assertThat(store.workspace("reply-workspace")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM slack_reply", Integer.class)).isZero();
        }
    }

    @Test
    void migratesTwentySixPreservingEvidenceAndBindingBrowserJobsToTheirWorkspace() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('browser-workspace','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle(id,workspace_id,question,status,used_characters,payload_json,digest,created_at) VALUES('browser-bundle','browser-workspace','question','NO_EVIDENCE',0,'{}','unchanged','now')");
            jdbc.execute("DROP TABLE browser_investigation");
            jdbc.execute("PRAGMA user_version=26");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT digest FROM evidence_bundle WHERE id='browser-bundle'", String.class)).isEqualTo("unchanged");
            jdbc.update("INSERT INTO browser_investigation(digest,workspace_id,bundle_id,bundle_digest,scope_json,created_at,expires_at,updated_at,request_id) VALUES('preview','browser-workspace','browser-bundle','unchanged','{}','now','later','now','request')");
            jdbc.update("INSERT INTO slack_inbox_route VALUES('team/channel','browser-workspace','{}')");
            jdbc.update("INSERT INTO slack_inbox_item VALUES('message','browser-workspace','{}')");
            jdbc.update("INSERT INTO slack_inbox_thread VALUES('team/channel/thread','browser-workspace','thread')");
            jdbc.update("INSERT INTO slack_inbox_seen VALUES('team/event','now')");
            jdbc.update("INSERT INTO workspace_archive_import_receipt VALUES('import-request','digest','archive-preview','{}')");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO browser_investigation(digest,workspace_id,bundle_id,bundle_digest,scope_json,created_at,expires_at,updated_at,request_id) VALUES('duplicate','browser-workspace','browser-bundle','unchanged','{}','now','later','now','request')"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM browser_investigation", Integer.class)).isEqualTo(1);
            jdbc.update("DELETE FROM workspace WHERE id='browser-workspace'");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM browser_investigation", Integer.class)).isZero();
            for (String table : java.util.List.of("slack_inbox_route", "slack_inbox_item", "slack_inbox_thread")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_archive_import_receipt", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void migratesTwentyTwoWithoutChangingExistingPollingAndPreservesWorkspaceToggle() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,domain,polling_enabled,poll_interval_seconds,next_poll_at) VALUES('source','one','synthetic','URL','https://example.com','',1,7200,1234)");
            jdbc.execute("DROP TABLE workspace_source_polling");
            jdbc.execute("PRAGMA user_version=22");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT polling_enabled FROM source", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT next_poll_at FROM source", Long.class)).isEqualTo(1234L);
            jdbc.update("INSERT INTO workspace_source_polling(workspace_id,enabled,revision) VALUES('one',1,3)");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT revision FROM workspace_source_polling", Integer.class)).isEqualTo(3);
            jdbc.update("DELETE FROM workspace WHERE id='one'");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_source_polling", Integer.class)).isZero();
        }
    }

    @Test
    void migratesTwentyThreeAndKeepsUnknownDatesAndSavedTimerAcrossRestart() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,domain) VALUES('source','one','synthetic','URL','https://example.com','')");
            jdbc.update("INSERT INTO workspace_source_polling(workspace_id,enabled,revision) VALUES('one',0,2)");
            jdbc.execute("ALTER TABLE source DROP COLUMN created_at");
            jdbc.execute("ALTER TABLE workspace_source_polling DROP COLUMN interval_seconds");
            jdbc.execute("PRAGMA user_version=23");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT created_at FROM source", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT interval_seconds FROM workspace_source_polling", Integer.class)).isEqualTo(3600);
            jdbc.update("UPDATE workspace_source_polling SET interval_seconds=900");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT interval_seconds FROM workspace_source_polling", Integer.class)).isEqualTo(900);
            assertThat(jdbc.queryForObject("SELECT enabled FROM workspace_source_polling", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT revision FROM workspace_source_polling", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void migratesTwentyOneAndPreservesQuestionAttachmentsAcrossRestart() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle VALUES('bundle','one','question','READY',1,'json','hash','now')");
            jdbc.execute("DROP TABLE question_attachment");
            jdbc.execute("PRAGMA user_version=21");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            jdbc.update("INSERT INTO question_attachment VALUES('file','one','bundle','synthetic.txt','text/plain',?,'hash','synthetic',0,'now')", new byte[]{1, 2, 3});
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT content FROM question_attachment", byte[].class)).containsExactly(1, 2, 3);
            assertThat(jdbc.queryForObject("SELECT name FROM workspace", String.class)).isEqualTo("synthetic");
            jdbc.update("DELETE FROM evidence_bundle WHERE id='bundle'");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_attachment", Integer.class)).isZero();
        }
    }

    @Test
    void migratesTwentyAndPersistsCliPathsWithoutExecutingThem() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("DROP TABLE cli_launch_settings");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=20");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT codex_path FROM cli_launch_settings", String.class)).isEmpty();
            jdbc.update("UPDATE cli_launch_settings SET codex_path='/synthetic/bin/codex',revision=1");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT codex_path FROM cli_launch_settings", String.class)).isEqualTo("/synthetic/bin/codex");
            assertThat(jdbc.queryForObject("SELECT revision FROM cli_launch_settings", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void migratesNineteenAndPersistsHttpValidatorsWithSourceCascade() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,domain) VALUES('source','one','synthetic','URL','https://example.com','')");
            jdbc.execute("DROP TABLE source_http_cache");
            jdbc.execute("DROP TABLE cli_launch_settings");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=19");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_http_cache", Integer.class)).isZero();
            jdbc.update("INSERT INTO source_http_cache VALUES('source','https://example.com/final','synthetic-etag','synthetic-date')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT etag FROM source_http_cache", String.class)).isEqualTo("synthetic-etag");
            jdbc.update("DELETE FROM source WHERE id='source'");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM source_http_cache", Integer.class)).isZero();
        }
    }

    @Test
    void migratesEighteenAndPersistsPollingSettingsAcrossRestart() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO source(id,workspace_id,name,kind,location,domain) VALUES('source','one','synthetic','URL','https://example.com','')");
            removePolling(jdbc);
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=18");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT polling_enabled FROM source", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT next_poll_at FROM source", Long.class)).isNull();
            jdbc.update("UPDATE source SET polling_enabled=1,poll_interval_seconds=120,next_poll_at=1800000120000,polling_revision=1,consecutive_failures=2,status='SYNCING'");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT next_poll_at FROM source", Long.class)).isEqualTo(1800000120000L);
            assertThat(jdbc.queryForObject("SELECT polling_revision FROM source", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM source", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM source", String.class)).isEqualTo("ERROR");
        }
    }

    private void removePolling(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE cli_launch_settings");
        jdbc.execute("DROP TABLE source_http_cache");
        jdbc.execute("DROP INDEX source_poll_due");
        jdbc.execute("ALTER TABLE source DROP COLUMN next_poll_at");
        jdbc.execute("ALTER TABLE source DROP COLUMN consecutive_failures");
        jdbc.execute("ALTER TABLE source DROP COLUMN polling_revision");
    }

    private void removeConversations(JdbcTemplate jdbc) {
        removePolling(jdbc);
        jdbc.execute("DROP TABLE conversation_request");
        jdbc.execute("DROP TABLE conversation_turn");
        jdbc.execute("DROP TABLE conversation");
        jdbc.execute("DROP INDEX answer_job_scope");
    }

    @Test
    void migratesVersionSeventeenWithoutRewritingRoomsAndPersistsFrozenRequests() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle VALUES('bundle','one','question','READY',1,'json','hash','now')");
            jdbc.update("INSERT INTO conversation VALUES('room','one','synthetic',0,'now')");
            jdbc.execute("DROP TABLE conversation_request");
            removePolling(jdbc);
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=17");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT title FROM conversation", String.class)).isEqualTo("synthetic");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_request", Integer.class)).isZero();
            jdbc.update("INSERT INTO conversation_request VALUES('request','one','bundle','synthetic-snapshot')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT snapshot_json FROM conversation_request", String.class)).isEqualTo("synthetic-snapshot");
            jdbc.update("DELETE FROM evidence_bundle");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_request", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation", Integer.class)).isEqualTo(1);
        }
    }
    @Test
    void migratesVersionSixteenPreservingJobsAndPersistsOrderedLinks() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            removeConversations(jdbc);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle VALUES('bundle','one','question','READY',1,'json','hash','now')");
            jdbc.update("INSERT INTO answer_job VALUES('job','one','bundle','approval','CODEX','model','digest','SUCCEEDED','answer',NULL,'now','now')");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=16");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT answer FROM answer_job", String.class)).isEqualTo("answer");
            jdbc.update("INSERT INTO conversation VALUES('room','one','synthetic',1,'now')");
            jdbc.update("INSERT INTO conversation_turn VALUES('one','room','job',1,'now')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT job_id FROM conversation_turn WHERE position=1", String.class)).isEqualTo("job");
            assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void migratesVersionFifteenAndInterruptsAnswerJobsWithoutReusingApproval() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle VALUES('bundle','one','question','READY',1,'json','hash','now')");
            jdbc.update("INSERT INTO answer_approval VALUES('bundle','approval','CODEX','model','digest','now','later',NULL)");
            removeConversations(jdbc);
            jdbc.execute("DROP TABLE answer_job");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=15");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT digest FROM answer_approval", String.class)).isEqualTo("digest");
            for (String status : java.util.List.of("QUEUED", "RUNNING", "CANCELLING", "SUCCEEDED", "CANCELLED", "FAILED")) {
                jdbc.update("INSERT INTO answer_job VALUES(?,'one','bundle',?,'CODEX','model','digest',?,'answer',NULL,'now','now')", status, status, status);
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_job WHERE status='INTERRUPTED'", Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT answer FROM answer_job WHERE status='SUCCEEDED'", String.class)).isEqualTo("answer");
                assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT approval_id) FROM answer_job", Integer.class)).isEqualTo(6);
            }
        }
    }

    @Test
    void migratesVersionFourteenPreservingBundlesAndApprovalAcrossRestart() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO evidence_bundle VALUES('bundle','one','question','READY',1,'synthetic-json','hash','now')");
            removeConversations(jdbc);
            jdbc.execute("DROP TABLE answer_job");
            jdbc.execute("DROP TABLE answer_approval");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=14");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT payload_json FROM evidence_bundle", String.class)).isEqualTo("synthetic-json");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
            jdbc.update("INSERT INTO answer_approval VALUES('bundle','approval','CODEX','model','digest','now','later',NULL)");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT digest FROM answer_approval", String.class)).isEqualTo("digest");
            jdbc.update("DELETE FROM evidence_bundle");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_approval", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void migratesVersionThirteenWithoutChangingPublicationAndCreatesEmptyBundleStorage() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',1,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'Title','text [E1]','DRAFT',1,'now')");
            jdbc.update("INSERT INTO knowledge_review VALUES('card',1,'reviewer','checked','now')");
            jdbc.update("INSERT INTO knowledge_publication VALUES('card',1,1,'now')");
            removeConversations(jdbc);
            jdbc.execute("DROP TABLE answer_job");
            jdbc.execute("DROP TABLE answer_approval");
            jdbc.execute("DROP TABLE evidence_bundle");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=13");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_bundle", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT markdown FROM knowledge_version", String.class)).isEqualTo("text [E1]");
            }
        }
    }

    @Test
    void migratesVersionTwelveAndQueuesExistingPublicationsWithoutChangingDrafts() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            removeSearchTriggers(jdbc);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',2,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'Published','public [E1]','DRAFT',1,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',2,'Private','draft','DRAFT',1,'now')");
            jdbc.update("INSERT INTO knowledge_review VALUES('card',1,'reviewer','checked','now')");
            jdbc.update("INSERT INTO knowledge_publication VALUES('card',1,1,'now')");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=12");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE entry_id='knowledge:card'", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT latest_version FROM knowledge_card", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT markdown FROM knowledge_version ORDER BY number", String.class)).containsExactly("public [E1]", "draft");
            }
        }
    }

    private void removeSearchTriggers(JdbcTemplate jdbc) {
        removeConversations(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS answer_job");
        jdbc.execute("DROP TABLE IF EXISTS answer_approval");
        jdbc.execute("DROP TABLE IF EXISTS evidence_bundle");
        for (String trigger : java.util.List.of("publication_search_insert", "knowledge_search_delete",
                "source_knowledge_search_delete", "source_knowledge_search_domain")) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @Test
    void migratesVersionElevenAcceptanceAsFullWithoutChangingItsVersionOrNote() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,result_card_id,created_at,updated_at) VALUES('job','one','card','CODEX',1,'fixture','SUCCEEDED','result','now','now')");
            jdbc.update("INSERT INTO proposal_acceptance(workspace_id,job_id,source_version,result_version,applied_version,decision_revision,note,accepted_at) VALUES('one','job',1,1,2,0,'existing adoption','now')");
            removeSearchTriggers(jdbc);
            jdbc.execute("ALTER TABLE proposal_acceptance DROP COLUMN mode");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=11");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
                assertThat(jdbc.queryForObject("SELECT mode FROM proposal_acceptance", String.class)).isEqualTo("FULL");
                assertThat(jdbc.queryForObject("SELECT applied_version FROM proposal_acceptance", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT note FROM proposal_acceptance", String.class)).isEqualTo("existing adoption");
            }
        }
    }

    @Test
    void migratesVersionTenWithoutInventingAcceptanceAndPersistsItOnRestart() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,result_card_id,created_at,updated_at) VALUES('job','one','card','CODEX',1,'fixture','SUCCEEDED','result','now','now')");
            jdbc.update("INSERT INTO proposal_decision VALUES('one','job',1,'OPEN',1,1,'existing history','now')");
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=10");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_acceptance", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT note FROM proposal_decision", String.class)).isEqualTo("existing history");
            jdbc.update("INSERT INTO proposal_acceptance(workspace_id,job_id,source_version,result_version,applied_version,decision_revision,note,accepted_at) VALUES('one','job',1,1,2,1,'accepted fixture','now')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT applied_version FROM proposal_acceptance", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_decision", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void migratesVersionNineAndPreservesDecisionHistoryOnReopen() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO generation_job(id,workspace_id,source_card_id,provider,expected_version,digest,status,result_card_id,created_at,updated_at) VALUES('job','one','card','CODEX',1,'fixture','SUCCEEDED','result','now','now')");
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=9");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_decision", Integer.class)).isZero();
            jdbc.update("INSERT INTO proposal_decision VALUES('one','job',1,'REJECTED',1,1,'synthetic note','now')");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT note FROM proposal_decision", String.class)).isEqualTo("synthetic note");
            assertThat(jdbc.queryForObject("SELECT status FROM generation_job", String.class)).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void migratesVersionEightFoldersAndReopensWithoutLosingPlacementOrPublication() throws Exception {
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO workspace VALUES('one','synthetic','now')");
            jdbc.update("INSERT INTO knowledge_card VALUES('card','one',1,'now')");
            jdbc.update("INSERT INTO knowledge_version VALUES('card',1,'Human','edited','DRAFT',1,'now')");
            jdbc.update("INSERT INTO knowledge_review VALUES('card',1,'synthetic','checked','now')");
            jdbc.update("INSERT INTO knowledge_publication VALUES('card',1,1,'now')");
            jdbc.update("INSERT INTO knowledge_folder(id,workspace_id,parent_id,name) VALUES('root','one',NULL,'root'),('child','one','root','child')");
            jdbc.update("INSERT INTO knowledge_placement VALUES('card','child',3)");
            removeSearchTriggers(jdbc);
            jdbc.execute("ALTER TABLE knowledge_folder DROP COLUMN revision");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=8");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
                assertThat(jdbc.queryForList("SELECT revision FROM knowledge_folder", Integer.class)).containsOnly(1);
                assertThat(jdbc.queryForObject("SELECT parent_id FROM knowledge_folder WHERE id='child'", String.class)).isEqualTo("root");
                assertThat(jdbc.queryForObject("SELECT revision FROM knowledge_placement", Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT markdown FROM knowledge_version", String.class)).isEqualTo("edited");
                assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
            }
        }
    }

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
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE generation_job");
            jdbc.execute("DROP TABLE knowledge_evidence");
            jdbc.execute("DROP TABLE evidence_snapshot");
            jdbc.execute("DROP TABLE knowledge_version");
            jdbc.execute("DROP TABLE knowledge_card");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=2");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE generation_job");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=3");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version_status");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_checked_at");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN requested_model");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=4");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_version_status");
            jdbc.execute("ALTER TABLE generation_job DROP COLUMN cli_checked_at");
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=5");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE knowledge_publication");
            jdbc.execute("DROP TABLE knowledge_review");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=6");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
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
            removeSearchTriggers(jdbc);
            jdbc.execute("DROP TABLE knowledge_placement");
            jdbc.execute("DROP TABLE knowledge_folder");
            jdbc.execute("DROP TABLE proposal_decision");
            jdbc.execute("DROP TABLE proposal_acceptance");
            jdbc.execute("DROP TABLE IF EXISTS question_attachment");
            jdbc.execute("PRAGMA user_version=7");
        }
        try (var source = (HikariDataSource) new DatabaseConfiguration().dataSource(directory.toString())) {
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("PRAGMA user_version", Integer.class)).isEqualTo(29);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_placement", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT markdown FROM knowledge_version", String.class)).isEqualTo("edited");
            assertThat(jdbc.queryForObject("SELECT version FROM knowledge_publication", Integer.class)).isEqualTo(1);
            jdbc.update("INSERT INTO knowledge_folder(id,workspace_id,parent_id,name) VALUES('folder','one',NULL,'synthetic')");
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
