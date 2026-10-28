package dev.dougle.plugins.adapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MysqlReadRuntimeSmoke {
    private static int passed;
    private static final class ObservedClient extends MysqlClient {
        int rollbacks;
        int opened;
        boolean measureWire;
        long maxReturnedFieldBytes;
        long maxReturnedRowBytes;
        long maxServerBytesSentDelta;
        Checked afterMetadata;
        boolean metadataBooleanObserved;
        int lastSqlErrorCode;
        private static long bytesSent(Connection connection) throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Bytes_sent'")) {
                if (!rows.next()) throw new SQLException("Missing server byte counter");
                return rows.getLong(2);
            }
        }
        @Override Connection open(Config config, int timeout) throws SQLException {
            Connection actual = super.open(config, timeout);
            opened++;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                try {
                    Object result = method.invoke(actual, arguments);
                    if (method.getName().equals("rollback")) rollbacks++;
                    if (result instanceof java.sql.Statement statement) {
                        boolean measured = measureWire && arguments != null && arguments.length > 0
                                && arguments[0] instanceof String sql && (sql.contains(" FROM `fixture`.`large_items`") || sql.contains(" FROM `fixture`.`drift_items`"));
                        boolean metadata = arguments != null && arguments.length > 0 && arguments[0] instanceof String sql
                                && sql.contains("FROM information_schema.COLUMNS") && sql.contains("GENERATION_EXPRESSION");
                        Class<?> contract = statement instanceof java.sql.PreparedStatement ? java.sql.PreparedStatement.class : java.sql.Statement.class;
                        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (statementProxy, operation, parameters) -> {
                            try {
                                long bytesBefore = measured && operation.getName().equals("executeQuery") ? bytesSent(actual) : 0;
                                Object value = operation.invoke(statement, parameters);
                                if (metadata && operation.getName().equals("close") && afterMetadata != null) {
                                    Checked callback = afterMetadata;
                                    afterMetadata = null;
                                    callback.run();
                                }
                                if (value instanceof java.sql.ResultSet rows) {
                                    return Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, (rowProxy, getter, getterArguments) -> {
                                        try {
                                            Object returned = getter.invoke(rows, getterArguments);
                                            if (metadata && getter.getName().equals("next") && Boolean.TRUE.equals(returned)) {
                                                byte[] flag = rows.getBytes(4);
                                                if (flag != null && flag.length > 1) throw new AssertionError("Metadata expression was not reduced to boolean");
                                                metadataBooleanObserved = true;
                                            }
                                            if (measured && getter.getName().equals("next") && Boolean.TRUE.equals(returned)) {
                                                long rowBytes = 0;
                                                for (int field = 1; field <= rows.getMetaData().getColumnCount(); field++) {
                                                    byte[] payload = rows.getBytes(field);
                                                    int length = payload == null ? 0 : payload.length;
                                                    maxReturnedFieldBytes = Math.max(maxReturnedFieldBytes, length);
                                                    rowBytes += length;
                                                }
                                                maxReturnedRowBytes = Math.max(maxReturnedRowBytes, rowBytes);
                                            }
                                            if (measured && getter.getName().equals("close")) {
                                                maxServerBytesSentDelta = Math.max(maxServerBytesSentDelta, bytesSent(actual) - bytesBefore);
                                            }
                                            return returned;
                                        }
                                        catch (InvocationTargetException failure) {
                                            System.err.println("Synthetic JDBC result " + getter.getName() + ": " + failure.getCause());
                                            throw failure.getCause();
                                        }
                                    });
                                }
                                return value;
                            }
                            catch (InvocationTargetException failure) {
                                if (failure.getCause() instanceof SQLException sqlFailure) lastSqlErrorCode = sqlFailure.getErrorCode();
                                System.err.println("Synthetic JDBC operation " + operation.getName() + ": " + failure.getCause());
                                throw failure.getCause();
                            }
                        });
                    }
                    return result;
                } catch (InvocationTargetException failure) {
                    System.err.println("Synthetic JDBC connection " + method.getName() + ": " + failure.getCause());
                    throw failure.getCause();
                }
            });
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }
    private static void denied(Checked action, Class<? extends Exception> expected, String label) throws Exception {
        try { action.run(); } catch (Exception failure) {
            if (!expected.isInstance(failure)) throw failure;
            check(true, label);
            return;
        }
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int port = Integer.parseInt(input.readLine());
        var config = new MysqlClient.Config("127.0.0.1", port, "fixture", "fixture_reader", input.readLine(), "REQUIRED");
        var client = new ObservedClient();
        var reader = new MysqlReadClient(client);
        var access = new MysqlReadClient.Access("fixture", Map.of("items", Set.of("id", "label", "binary_value", "derived"), "item_view", Set.of("id")), 3, 65536, Duration.ofSeconds(5));
        try (var snapshot = client.schema(config)) {
            check(snapshot.files().size() == 1 && new String(snapshot.files().getFirst().content(), StandardCharsets.UTF_8).contains("label"), "production schema collection over TLS");
        }
        var selected = reader.read(config, access, new MysqlReadClient.Request("items", List.of("label"), Map.of("id", "1"), 3), () -> {});
        check(selected.rows() == 1 && selected.output().contains("synthetic healthy") && !selected.truncated(), "selected real rows");
        var injection = reader.read(config, access, new MysqlReadClient.Request("items", List.of("label"), Map.of("label", "' OR 1=1 --"), 3), () -> {});
        check(injection.rows() == 0, "predicate injection remains data");
        var limited = reader.read(config, access, new MysqlReadClient.Request("items", List.of("label"), Map.of(), 1), () -> {});
        check(limited.rows() == 1 && limited.truncated(), "row cap with truncation");
        var small = new MysqlReadClient.Access("fixture", access.tables(), 3, 128, Duration.ofSeconds(5));
        var bytes = reader.read(config, small, new MysqlReadClient.Request("items", List.of("label"), Map.of("id", "2"), 3), () -> {});
        check(bytes.truncated() && bytes.output().getBytes(StandardCharsets.UTF_8).length <= 128, "128-byte output cap");
        denied(() -> reader.read(config, access, new MysqlReadClient.Request("unapproved", List.of("id"), Map.of(), 1), () -> {}), IllegalArgumentException.class, "unapproved table denied");
        for (String column : List.of("binary_value", "derived")) {
            denied(() -> reader.read(config, access, new MysqlReadClient.Request("items", List.of(column), Map.of(), 1), () -> {}), SQLException.class, column + " denied by real metadata");
        }
        denied(() -> reader.read(config, access, new MysqlReadClient.Request("item_view", List.of("id"), Map.of(), 1), () -> {}), SQLException.class, "view denied by real metadata");
        int before = client.rollbacks;
        int[] checkpoints = {0};
        denied(() -> reader.read(config, access, new MysqlReadClient.Request("items", List.of("id"), Map.of(), 1), () -> {
            if (++checkpoints[0] == 4) throw new IllegalStateException("synthetic cancellation");
        }), IllegalStateException.class, "cooperative cancellation");
        check(client.rollbacks == before + 1, "cancellation executes real JDBC rollback");
        var timed = new MysqlReadClient.Access("fixture", access.tables(), 3, 65536, Duration.ofSeconds(1));
        int[] timedChecks = {0};
        denied(() -> reader.read(config, timed, new MysqlReadClient.Request("items", List.of("id"), Map.of(), 1), () -> {
            if (++timedChecks[0] == 4) {
                try { Thread.sleep(1100); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        }), java.sql.SQLTimeoutException.class, "elapsed budget enforced with synthetic checkpoint delay");
        try (Connection connection = client.open(config)) {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                denied(() -> statement.executeUpdate("INSERT INTO fixture.items(id,label) VALUES (4,'forbidden')"), SQLException.class, "server SELECT-only grant rejects mutation");
                statement.execute("START TRANSACTION READ ONLY");
                var session = connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getSession().getServerSession();
                check(session.inTransactionOnServer(), "server reports transaction active");
                connection.rollback();
                check(!session.inTransactionOnServer(), "server reports transaction ends after rollback");
            }
        }
        System.out.println("Checks passed before numeric-column regression: " + passed);
        var regressions = new java.util.ArrayList<String>();
        try {
            var numeric = reader.read(config, access, new MysqlReadClient.Request("items", List.of("id"), Map.of("id", "1"), 3), () -> {});
            check(numeric.rows() == 1 && numeric.output().contains("1"), "numeric column real JDBC conversion");
        } catch (SQLException failure) { regressions.add("numeric column read: " + failure); }
        int[] earlyChecks = {0};
        try {
            denied(() -> reader.read(config, timed, new MysqlReadClient.Request("items", List.of("label"), Map.of(), 1), () -> {
                if (++earlyChecks[0] == 2) {
                    try { Thread.sleep(1100); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
            }), java.sql.SQLTimeoutException.class, "early timeout preserves timeout classification");
        } catch (SQLException failure) { regressions.add("early timeout classification: " + failure); }
        var largeAccess = new MysqlReadClient.Access("fixture", Map.of("large_items", Set.of("id", "body")), 3, 1024, Duration.ofSeconds(5));
        client.measureWire = true;
        for (String id : List.of("1", "2")) {
            try (Connection connection = client.open(config);
                 var query = connection.prepareStatement("SELECT OCTET_LENGTH(body) FROM fixture.large_items WHERE id = ?")) {
                query.setString(1, id);
                try (var rows = query.executeQuery()) {
                    check(rows.next() && rows.getLong(1) == 8388608, "large LONGTEXT " + id + " stored source is exactly 8 MiB");
                }
            }
            client.maxReturnedFieldBytes = 0;
            client.maxReturnedRowBytes = 0;
            client.maxServerBytesSentDelta = 0;
            var large = reader.read(config, largeAccess, new MysqlReadClient.Request("large_items", List.of("body"), Map.of("id", id), 1), () -> {});
            check(large.truncated() && large.output().getBytes(StandardCharsets.UTF_8).length <= 1024, "large LONGTEXT " + id + " output bounded and explicitly partial");
            check(client.maxReturnedFieldBytes > 0 && client.maxReturnedFieldBytes <= 1024 && client.maxReturnedRowBytes <= 1025, "large LONGTEXT " + id + " server-returned field and row payload bounded");
            check(client.maxServerBytesSentDelta > 0 && client.maxServerBytesSentDelta <= 9216, "large LONGTEXT " + id + " actual server Bytes_sent delta bounded");
            System.out.println("WIRE fixture=" + id + " sourceBytes=8388608 maxFieldBytes=" + client.maxReturnedFieldBytes
                    + " maxRowBytes=" + client.maxReturnedRowBytes + " serverBytesSentDelta=" + client.maxServerBytesSentDelta);
        }
        var prefix = reader.read(config, largeAccess, new MysqlReadClient.Request("large_items", List.of("body"), Map.of("body", "p".repeat(512)), 1), () -> {});
        check(prefix.rows() == 0 && !prefix.truncated(), "text predicate does not match truncated prefix");
        var exact = reader.read(config, largeAccess, new MysqlReadClient.Request("large_items", List.of("body"), Map.of("body", "p".repeat(512) + "tail"), 1), () -> {});
        check(exact.rows() == 1 && exact.truncated() && exact.output().endsWith("]\n[TRUNCATED]\n"), "full text equality returns one complete serialized row with partial cell flag");
        for (String id : List.of("4", "5")) {
            var boundary = reader.read(config, largeAccess, new MysqlReadClient.Request("large_items", List.of("body"), Map.of("id", id), 1), () -> {});
            check(boundary.rows() == 1 && !boundary.truncated(), "NULL or exact 256-character boundary " + id + " not falsely truncated");
        }
        var scalarAccess = new MysqlReadClient.Access("fixture", Map.of("scalar_items", Set.of("id", "amount", "day_value", "time_value", "moment", "absent")), 1, 4096, Duration.ofSeconds(5));
        var scalar = reader.read(config, scalarAccess, new MysqlReadClient.Request("scalar_items", List.of("id", "amount", "day_value", "time_value", "moment", "absent"), Map.of("id", "1"), 1), () -> {});
        check(scalar.rows() == 1 && !scalar.truncated() && scalar.output().equals("[{\"id\":\"1\",\"amount\":\"12345.67\",\"day_value\":\"2026-10-25\",\"time_value\":\"12:34:56\",\"moment\":\"2026-10-25 12:34:56\",\"absent\":null}]"), "uniform SQL conversion preserves numeric temporal and NULL JSON values");
        check(client.metadataBooleanObserved, "generation expression metadata returns at most one byte per flag");
        var ddlConfig = new MysqlClient.Config("127.0.0.1", port, "fixture", "fixture_ddl", config.password(), "REQUIRED");
        client.afterMetadata = () -> {
            try (Connection connection = new MysqlClient().open(ddlConfig)) {
                connection.setReadOnly(false);
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    statement.execute("ALTER TABLE fixture.drift_items MODIFY body LONGTEXT");
                    statement.executeUpdate("UPDATE fixture.drift_items SET body=REPEAT('d',8388608) WHERE id=1");
                }
            }
        };
        client.maxReturnedFieldBytes = 0;
        client.maxReturnedRowBytes = 0;
        client.maxServerBytesSentDelta = 0;
        var driftAccess = new MysqlReadClient.Access("fixture", Map.of("drift_items", Set.of("id", "body")), 1, 1024, Duration.ofSeconds(10));
        var driftRequest = new MysqlReadClient.Request("drift_items", List.of("body"), Map.of("id", "1"), 1);
        try {
            var concurrent = reader.read(config, driftAccess, driftRequest, () -> {});
            check(client.afterMetadata == null && concurrent.rows() == 1 && concurrent.truncated()
                    && client.maxReturnedFieldBytes <= 1024 && client.maxServerBytesSentDelta <= 9216,
                    "concurrent DDL either returns bounded partial data or fails closed");
            System.out.println("DDL_OUTCOME=bounded_result");
        } catch (SQLException failure) {
            if (client.lastSqlErrorCode != 1412 || client.afterMetadata != null) throw failure;
            check(client.maxReturnedFieldBytes == 0, "concurrent DDL either returns bounded partial data or fails closed");
            System.out.println("DDL_OUTCOME=server_rejected_changed_definition errorCode=1412 no_result_payload");
        }
        var drift = reader.read(config, driftAccess, driftRequest, () -> {});
        check(drift.rows() == 1 && drift.truncated() && drift.output().contains("ddd"), "fresh transaction after DDL reads bounded LONGTEXT with partial flag");
        check(client.maxReturnedFieldBytes <= 1024 && client.maxServerBytesSentDelta > 0 && client.maxServerBytesSentDelta <= 9216, "concurrent numeric-to-LONGTEXT DDL cannot restore unbounded wire payload");
        System.out.println("WIRE fixture=ddl-drift sourceBytes=8388608 maxFieldBytes=" + client.maxReturnedFieldBytes + " serverBytesSentDelta=" + client.maxServerBytesSentDelta);
        System.out.println("Synthetic MySQL checks passed: " + passed);
        if (!regressions.isEmpty()) throw new AssertionError("Real runtime regressions: " + regressions);
    }
}
