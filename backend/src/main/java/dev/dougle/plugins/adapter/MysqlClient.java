package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.adapter.CollectionLimits;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

@Component
public class MysqlClient {
    public record Config(String host, int port, String database, String username, String password, String sslMode) {
        @Override public String toString() { return "MySQL connection [redacted]"; }
        public String location() { return host + ":" + port + "/" + database; }
        public Config withoutPassword() { return new Config(host, port, database, username, "", sslMode); }
    }

    public void validate(Config config) {
        if (config == null || config.host() == null || !config.host().matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                || config.port() < 1 || config.port() > 65535 || config.database() == null
                || !config.database().matches("[\\p{L}\\p{N}_$-]{1,64}") || config.username() == null
                || config.username().isBlank() || config.username().length() > 128 || config.password() == null
                || config.password().length() > 4096 || !List.of("VERIFY_IDENTITY", "REQUIRED", "DISABLED").contains(config.sslMode() == null ? "" : config.sslMode()))
            throw new LibraryException(400, "호스트·포트·데이터베이스·사용자 ID·보안 설정을 확인하세요.");
        if (config.sslMode().equals("DISABLED") && !List.of("localhost", "127.0.0.1").contains(config.host()))
            throw new LibraryException(400, "암호화 해제는 localhost 또는 127.0.0.1에서만 가능합니다.");
    }

    Connection open(Config config) throws SQLException {
        return open(config, 15000);
    }

    Connection open(Config config, int timeoutMillis) throws SQLException {
        validate(config);
        if (timeoutMillis < 1 || timeoutMillis > 15000) throw new IllegalArgumentException("Invalid connection timeout");
        var properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("sslMode", config.sslMode());
        properties.setProperty("connectTimeout", Integer.toString(Math.min(5000, timeoutMillis)));
        properties.setProperty("socketTimeout", Integer.toString(timeoutMillis));
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowUrlInLocalInfile", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("autoDeserialize", "false");
        var connection = DriverManager.getConnection("jdbc:mysql://" + config.host() + ":" + config.port() + "/", properties);
        try { connection.setReadOnly(true); return connection; }
        catch (SQLException exception) { connection.close(); throw exception; }
    }

    public void test(Config config) { schema(config); }

    public CollectedSnapshot schema(Config config) {
        validate(config);
        try (var connection = open(config)) {
            try (var check = connection.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
                check.setString(1, config.database());
                check.setQueryTimeout(10);
                try (var result = check.executeQuery()) {
                    if (!result.next()) throw new LibraryException(400, "데이터베이스가 없거나 조회 권한이 없습니다. DB명과 계정 권한을 확인하세요.");
                }
            }
            var text = new StringBuilder("Database: ").append(config.database()).append('\n');
            try (var query = connection.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION LIMIT 5001")) {
                query.setString(1, config.database());
                query.setQueryTimeout(10);
                int count = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (++count > 5000) throw new LibraryException(413, "DB 열이 5,000개를 초과합니다. 더 작은 데이터베이스를 선택하세요.");
                        for (int column = 1; column <= 6; column++) text.append(rows.getString(column)).append(column == 6 ? '\n' : '\t');
                        if (text.length() > 2_000_000) throw new LibraryException(413, "DB 구조가 너무 큽니다. 더 작은 데이터베이스를 선택하세요.");
                    }
                }
            }
            return CollectionLimits.snapshot(List.of(new CollectedSnapshot.File("mysql-schema.txt", text.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (SQLException exception) {
            String state = exception.getSQLState();
            if (state != null && state.startsWith("28")) throw new LibraryException(400, "MySQL 인증 실패: 사용자 ID·비밀번호·접속 허용 호스트를 확인하세요.");
            throw new LibraryException(502, "MySQL 연결 실패: 호스트·포트·방화벽·TLS 인증서를 확인하세요.");
        }
    }
}
