package dev.dougle.library.adapter.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;

final class LegacyDatabaseMigration {
    static void migrate(Path root) throws java.io.IOException {
        Path legacy = root.resolve("knowledge-forge.sqlite");
        Path target = root.resolve("dougle.sqlite");
        if (!Files.exists(legacy, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(legacy) || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.io.IOException("기존 DB와 새 DB를 자동 병합하지 않습니다. 자료 폴더를 확인하세요.");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacy);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!result.next() || result.getInt(1) != 0) throw new java.io.IOException("기존 DB가 사용 중입니다. 이전 앱을 종료하세요.");
        } catch (java.sql.SQLException exception) { throw new java.io.IOException("기존 DB를 안전하게 이전하지 못했습니다.", exception); }
        Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(root.resolve("knowledge-forge.sqlite-wal"));
        Files.deleteIfExists(root.resolve("knowledge-forge.sqlite-shm"));
    }
}
