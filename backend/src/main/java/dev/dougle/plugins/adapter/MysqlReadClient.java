package dev.dougle.plugins.adapter;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public final class MysqlReadClient {
    private static final Set<String> TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "bigint", "decimal",
            "float", "double", "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "date", "datetime",
            "timestamp", "time", "year", "enum", "set");
    private static final String TRUNCATED = "\n[TRUNCATED]\n";
    private final MysqlClient connections;

    public MysqlReadClient(MysqlClient connections) { this.connections = Objects.requireNonNull(connections); }

    public record Request(String table, List<String> columns, Map<String, String> equalities, int limit) {
        public Request {
            identifier(table);
            columns = List.copyOf(columns);
            equalities = Collections.unmodifiableSortedMap(new TreeMap<>(equalities));
            if (columns.isEmpty() || columns.size() > 16 || new HashSet<>(columns).size() != columns.size()
                    || equalities.size() > 16 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid read request");
            columns.forEach(MysqlReadClient::identifier);
            equalities.forEach((column, value) -> {
                identifier(column);
                if (value == null || value.length() > 1024) throw new IllegalArgumentException("Invalid equality value");
            });
        }

        public String key() {
            StringBuilder key = new StringBuilder(table).append(':').append(limit);
            columns.forEach(column -> key.append(':').append(column.length()).append(':').append(column));
            key.append('|');
            equalities.forEach((column, value) -> key.append(':').append(column.length()).append(':').append(column)
                    .append(':').append(value.length()).append(':').append(value));
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(key.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable");
            }
        }

        @Override public String toString() { return "MySQL read request [redacted]"; }
    }

    public record Access(String schema, Map<String, Set<String>> tables, int maxRows, int maxBytes, Duration timeout) {
        public Access {
            identifier(schema);
            if (Set.of("mysql", "sys", "information_schema", "performance_schema").contains(schema.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("System schema forbidden");
            }
            Map<String, Set<String>> copy = new TreeMap<>();
            tables.forEach((table, columns) -> {
                identifier(table);
                if (columns.isEmpty() || columns.size() > 64) throw new IllegalArgumentException("Invalid allowed columns");
                columns.forEach(MysqlReadClient::identifier);
                copy.put(table, Set.copyOf(columns));
            });
            tables = Map.copyOf(copy);
            if (tables.isEmpty() || tables.size() > 64 || maxRows < 1 || maxRows > 200 || maxBytes < 64 || maxBytes > 65536
                    || timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
                throw new IllegalArgumentException("Invalid read limits");
            }
        }
    }

    public record Result(String output, int rows, boolean truncated) {}

    public Result read(MysqlClient.Config config, Access access, Request request, Runnable checkpoint) throws SQLException {
        Objects.requireNonNull(checkpoint);
        validate(config, access, request);
        long started = System.nanoTime();
        Runnable cancellation = checkpoint;
        checkpoint.run();
        try (Connection connection = connections.open(config, (int) Math.max(1, access.timeout().toMillis()))) {
            boolean transactionBegun = false;
            Throwable primaryFailure = null;
            try {
                check(cancellation, started, access);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                transactionBegun = true;
                try (Statement transaction = connection.createStatement()) {
                    configure(transaction, connection, cancellation, started, access);
                    transaction.execute("START TRANSACTION READ ONLY");
                }
                verifyMetadata(connection, config, access, request, cancellation, started);
                return select(connection, access, request, cancellation, started);
            } catch (SQLException | IOException | RuntimeException | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                if (transactionBegun) {
                    try { connection.rollback(); }
                    catch (SQLException failure) {
                        if (primaryFailure == null) throw failure;
                        primaryFailure.addSuppressed(new SQLException("MySQL rollback failed"));
                    }
                }
            }
        } catch (SQLTimeoutException timeout) {
            throw new SQLTimeoutException("MySQL read time budget exceeded");
        } catch (SQLException | IOException failure) {
            throw new SQLException("MySQL read failed");
        }
    }

    public void validate(MysqlClient.Config config, Access access, Request request) {
        Objects.requireNonNull(access);
        Objects.requireNonNull(request);
        connections.validate(config);
        Set<String> allowed = access.tables().get(request.table());
        if (!access.schema().equals(config.database()) || allowed == null || !allowed.containsAll(request.columns())
                || !allowed.containsAll(request.equalities().keySet()) || request.limit() > access.maxRows()) {
            throw new IllegalArgumentException("Read request outside approved access");
        }
    }

    private void verifyMetadata(Connection connection, MysqlClient.Config config, Access access, Request request,
                                Runnable checkpoint, long started) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            configure(query, connection, checkpoint, started, access);
            query.setMaxRows(1);
            query.setString(1, config.database());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !config.database().equals(rows.getString(1))) throw new SQLException("Schema unavailable");
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            configure(query, connection, checkpoint, started, access);
            query.setMaxRows(1);
            query.setString(1, config.database());
            query.setString(2, request.table());
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !"BASE TABLE".equals(rows.getString(1))) throw new SQLException("Base table required");
            }
        }
        Set<String> required = new HashSet<>(request.columns());
        required.addAll(request.equalities().keySet());
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, EXTRA, COALESCE(CHAR_LENGTH(GENERATION_EXPRESSION), 0) > 0 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            configure(query, connection, checkpoint, started, access);
            query.setMaxRows(1025);
            query.setString(1, config.database());
            query.setString(2, request.table());
            try (var rows = query.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    check(checkpoint, started, access);
                    if (++count > 1024) throw new SQLException("Metadata limit exceeded");
                    String name = rows.getString(1);
                    if (!required.contains(name)) continue;
                    String type = rows.getString(2);
                    String extra = rows.getString(3);
                    boolean generated = rows.getBoolean(4);
                    if (!TYPES.contains(type) || extra != null && extra.toUpperCase(java.util.Locale.ROOT).contains("GENERATED")
                            || generated) throw new SQLException("Unsupported column");
                    required.remove(name);
                }
            }
        }
        if (!required.isEmpty()) throw new SQLException("Unknown columns");
    }

    private Result select(Connection connection, Access access, Request request, Runnable checkpoint, long started)
            throws SQLException, IOException {
        int fieldCharacters = Math.max(1, access.maxBytes() / 4 / request.columns().size());
        List<String> fields = new java.util.ArrayList<>();
        for (String column : request.columns()) {
            fields.add("LEFT(CONVERT(" + quoted(column) + " USING utf8mb4), " + fieldCharacters + ")");
        }
        for (String column : request.columns()) {
            fields.add("CHAR_LENGTH(CONVERT(" + quoted(column) + " USING utf8mb4)) > " + fieldCharacters);
        }
        String projection = String.join(", ", fields);
        List<String> predicates = request.equalities().keySet().stream().map(column -> quoted(column) + " = ?").toList();
        String sql = "SELECT " + projection + " FROM " + quoted(access.schema()) + "." + quoted(request.table())
                + (predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates)) + " LIMIT ?";
        BoundedText output = new BoundedText(access.maxBytes());
        int count = 0;
        boolean sourceTruncated = false;
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            configure(query, connection, checkpoint, started, access);
            query.setMaxRows(request.limit() + 1);
            query.setFetchSize(Integer.MIN_VALUE);
            int parameter = 1;
            for (String value : request.equalities().values()) query.setString(parameter++, value);
            query.setInt(parameter, request.limit() + 1);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    check(checkpoint, started, access);
                    if (count == request.limit()) { output.truncate(); break; }
                    for (int column = 0; column < request.columns().size(); column++) {
                        if (rows.getBoolean(request.columns().size() + column + 1)) sourceTruncated = true;
                    }
                    if (!output.append(count == 0 ? "[" : ",")) break;
                    boolean complete = output.append("{");
                    for (int column = 0; complete && column < request.columns().size(); column++) {
                        check(checkpoint, started, access);
                        complete = output.append((column == 0 ? "" : ",") + "\"" + request.columns().get(column) + "\":");
                        if (!complete) break;
                        try (Reader value = rows.getCharacterStream(column + 1)) {
                            if (value == null) complete = output.append("null");
                            else {
                                complete = output.append("\"");
                                int character;
                                while (complete && (character = value.read()) != -1) {
                                    check(checkpoint, started, access);
                                    complete = output.append(escaped((char) character));
                                }
                                if (complete) complete = output.append("\"");
                            }
                        }
                    }
                    if (!complete || !output.append("}")) break;
                    count++;
                }
            }
        }
        if (count > 0 && !output.truncated) output.append("]");
        if (sourceTruncated) output.truncate();
        check(checkpoint, started, access);
        return new Result(output.finish(), count, output.truncated);
    }

    private static void configure(Statement statement, Connection connection, Runnable checkpoint, long started, Access access)
            throws SQLException {
        check(checkpoint, started, access);
        long remaining = access.timeout().toNanos() - (System.nanoTime() - started);
        int millis = (int) Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
        connection.setNetworkTimeout(Runnable::run, millis);
        statement.setQueryTimeout(Math.max(1, (millis + 999) / 1000));
    }

    private static void check(Runnable checkpoint, long started, Access access) throws SQLTimeoutException {
        checkpoint.run();
        if (System.nanoTime() - started >= access.timeout().toNanos()) throw new SQLTimeoutException("Read timed out");
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid SQL identifier");
    }

    private static String quoted(String identifier) { return "`" + identifier + "`"; }

    private static String escaped(char character) {
        if (character == '"') return "\\\"";
        if (character == '\\') return "\\\\";
        if (character < 32 || Character.isSurrogate(character)) {
            String hex = Integer.toHexString(character);
            return "\\u" + "0".repeat(4 - hex.length()) + hex;
        }
        return Character.toString(character);
    }

    private static final class BoundedText {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private int bytes;
        private boolean truncated;

        private BoundedText(int limit) { this.limit = limit - TRUNCATED.length(); }

        private boolean append(String value) {
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > limit) { truncated = true; return false; }
            text.append(value);
            bytes += size;
            return true;
        }

        private void truncate() { truncated = true; }

        private String finish() { return text + (truncated ? TRUNCATED : ""); }
    }
}
