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
        if (version > 1) {
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
        return source;
    }
}
