package dev.dougle.plugins.adapter;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.mysql", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MysqlAcceptanceTest {
    @TempDir static Path directory;
    private Process server;
    private int port;
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final MysqlClient client = new MysqlClient();
    private final String password = "synthetic-fixture-only";

    @BeforeAll void prepare() throws Exception {
        String executable = System.getenv("FORGE_ACCEPTANCE_MYSQLD");
        assertThat(executable).isNotBlank();
        Path data = Files.createDirectory(directory.resolve("data"));
        var initialize = new ProcessBuilder(executable, "--no-defaults", "--initialize-insecure", "--datadir=" + data)
                .redirectErrorStream(true).redirectOutput(directory.resolve("initialize.log").toFile()).start();
        try {
            assertThat(initialize.waitFor(90, TimeUnit.SECONDS)).isTrue();
            assertThat(initialize.exitValue()).as("Isolated MySQL initialize; see owned log").isZero();
        } finally { if (initialize.isAlive()) initialize.destroyForcibly(); }
        try (var available = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = available.getLocalPort(); }
        server = new ProcessBuilder(executable, "--no-defaults", "--datadir=" + data, "--bind-address=127.0.0.1", "--port=" + port,
                "--mysqlx=OFF", "--mysql-native-password=ON", "--socket=" + directory.resolve("mysql.sock"),
                "--pid-file=" + directory.resolve("mysql.pid"), "--log-error=" + directory.resolve("server.log"))
                .redirectErrorStream(true).redirectOutput(directory.resolve("stdout.log").toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        java.sql.Connection setup = null;
        while (setup == null && System.nanoTime() < deadline && server.isAlive()) {
            try { setup = DriverManager.getConnection(adminUrl(), "root", ""); }
            catch (java.sql.SQLException unavailable) { Thread.sleep(100); }
        }
        assertThat(setup).as("Owned loopback MySQL must start; no fallback to another database").isNotNull();
        try (var connection = setup; var sql = connection.createStatement()) {
            for (String database : List.of("synthetic_a", "synthetic_b")) {
                sql.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
                sql.execute("CREATE TABLE " + database + ".inbound_receipt (id VARCHAR(40) PRIMARY KEY, state VARCHAR(80), note VARCHAR(80), occurred DATETIME)");
                sql.execute("INSERT INTO " + database + ".inbound_receipt VALUES ('TEST-42','" + (database.endsWith("a") ? "RECEIVED" : "OTHER_SCHEMA") + "','가상 😀', '2026-10-27 10:05:00'),('TEST-43','OTHER_ORDER',NULL,NULL)");
            }
            sql.execute("CREATE USER 'synthetic_reader'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '" + password + "'");
            sql.execute("GRANT SELECT ON synthetic_a.* TO 'synthetic_reader'@'127.0.0.1'");
            sql.execute("GRANT SELECT ON synthetic_b.* TO 'synthetic_reader'@'127.0.0.1'");
        }
    }
    private String adminUrl() { return "jdbc:mysql://127.0.0.1:" + port + "/?sslMode=DISABLED&connectTimeout=500&socketTimeout=1000"; }
    private MysqlClient.Config config(String database) { return new MysqlClient.Config("127.0.0.1", port, database, "synthetic_reader", password, "DISABLED"); }
    @AfterAll void finish() throws Exception {
        try {
            Files.writeString(Path.of("target/mysql-acceptance-results.json"), new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
        } finally {
            if (server != null && server.isAlive()) {
                server.destroy();
                if (!server.waitFor(15, TimeUnit.SECONDS)) { server.destroyForcibly(); server.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }
    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) for (int action = 0; action < 5; action++) {
            int selectedFixture = fixture;
            int selectedAction = action;
            String id = "T%04d".formatted(876 + fixture * 5 + action);
            tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
        }
        return tests.stream();
    }
    private void execute(String id, int fixture, int action) throws Exception {
        var record = new LinkedHashMap<String, Object>();
        record.put("id", id); record.put("startedAt", Instant.now().toString());
        record.put("scope", "REAL_MYSQL_8_4_OWNED_LOOPBACK_SERVER_SELECT_ONLY_ACCOUNT");
        record.put("fullAcceptancePassed", false);
        record.put("remaining", List.of("Natural language scope approval and code/schema evidence synthesis", "UI connection errors, actual model interpretation and app restart", "DATETIME has no timezone; timezone conversion not proven"));
        long started = System.nanoTime();
        try {
            var selected = config("synthetic_a");
            if (fixture == 2) {
                var bad = new MysqlClient.Config(selected.host(), port, selected.database(), selected.username(), "wrong-synthetic-password", "DISABLED");
                assertThatThrownBy(() -> client.schema(bad)).hasMessageContaining("인증 실패").hasMessageNotContaining("wrong-synthetic-password");
                if (action != 3) { record.put("status", "PARTIAL"); record.put("checkStatus", "PASS"); return; }
            }
            if (fixture == 0) {
                try (var snapshot = client.schema(selected)) {
                    String text = new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(text).contains("synthetic_a", "inbound_receipt", "state").doesNotContain("TEST-42", "synthetic_b");
                    record.put("schemaHash", snapshot.files().getFirst().hash());
                }
            } else {
                var access = new MysqlReadClient.Access("synthetic_a", Map.of("inbound_receipt", Set.of("id", "state", "note", "occurred")), 10, 4096, Duration.ofSeconds(5));
                var request = new MysqlReadClient.Request("inbound_receipt", List.of("id", "state", "note", "occurred"), Map.of("id", fixture == 4 && action == 1 ? "TEST-43" : "TEST-42"), 1);
                var reader = new MysqlReadClient(client);
                if (action == 2) {
                    assertThatThrownBy(() -> reader.read(selected, access, request, () -> { throw new java.util.concurrent.CancellationException(); }))
                            .isInstanceOf(java.util.concurrent.CancellationException.class);
                }
                var output = reader.read(selected, access, request, () -> {});
                assertThat(output.rows()).isEqualTo(1);
                assertThat(output.truncated()).isFalse();
                assertThat(output.output()).doesNotContain("OTHER_SCHEMA", password);
                var row = new JsonMapper().readTree(output.output()).get(0);
                if (fixture == 4 && action == 1) {
                    assertThat(row.path("id").asText()).isEqualTo("TEST-43");
                    assertThat(row.path("note").isNull()).isTrue();
                    assertThat(row.path("occurred").isNull()).isTrue();
                } else {
                    assertThat(row.path("id").asText()).isEqualTo("TEST-42");
                    assertThat(row.path("state").asText()).isEqualTo("RECEIVED");
                    assertThat(row.path("note").asText()).isEqualTo("가상 😀");
                    assertThat(row.path("occurred").asText()).isEqualTo("2026-10-27 10:05:00");
                }
                if (fixture == 3) assertThatThrownBy(() -> reader.read(config("synthetic_b"), access, request, () -> {})).isInstanceOf(IllegalArgumentException.class);
                record.put("output", output.output());
            }
            try (var connection = client.open(selected); var query = connection.prepareStatement("SELECT COUNT(*) FROM synthetic_a.inbound_receipt")) {
                assertThat(connection.isReadOnly()).isTrue();
                try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2); }
            }
            record.put("status", "PARTIAL"); record.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            record.put("status", "FAIL"); record.put("checkStatus", "FAIL"); record.put("failure", failure.toString()); throw failure;
        } finally { record.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); results.add(record); }
    }
}
