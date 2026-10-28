package dev.dougle.library.adapter.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyDatabaseMigrationTest {
    @TempDir Path root;
    @Test void preservesSchemaAndDataAndCanRepeat() throws Exception {
        Path legacy = root.resolve("knowledge-forge.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA user_version=30");
            statement.execute("CREATE TABLE synthetic(value TEXT)");
            statement.execute("INSERT INTO synthetic VALUES ('preserved')");
        }
        LegacyDatabaseMigration.migrate(root);
        LegacyDatabaseMigration.migrate(root);
        assertFalse(Files.exists(legacy));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("dougle.sqlite")); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT value FROM synthetic")) { assertTrue(result.next()); assertEquals("preserved", result.getString(1)); }
            try (var result = statement.executeQuery("PRAGMA user_version")) { assertTrue(result.next()); assertEquals(30, result.getInt(1)); }
        }
    }
    @Test void refusesCollisionWithoutChangingEitherFile() throws Exception {
        Files.writeString(root.resolve("knowledge-forge.sqlite"), "old");
        Files.writeString(root.resolve("dougle.sqlite"), "new");
        assertThrows(java.io.IOException.class, () -> LegacyDatabaseMigration.migrate(root));
        assertEquals("old", Files.readString(root.resolve("knowledge-forge.sqlite")));
        assertEquals("new", Files.readString(root.resolve("dougle.sqlite")));
    }
}
