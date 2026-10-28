package dev.dougle.plugins.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MysqlReadClientTest {
    private final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "reader", "synthetic-secret", "DISABLED");
    private final MysqlReadClient.Access access = access(2, 1024);
    private final MysqlReadClient.Request request = request("value");
    private MysqlClient connections;
    private MysqlReadClient client;
    private Connection connection;
    private Statement transaction;
    private PreparedStatement schema;
    private PreparedStatement table;
    private PreparedStatement columns;
    private PreparedStatement select;
    private ResultSet schemaRows;
    private ResultSet tableRows;
    private ResultSet columnRows;
    private ResultSet dataRows;

    @BeforeEach
    void prepare() throws Exception {
        connections = spy(new MysqlClient());
        client = new MysqlReadClient(connections);
        connection = mock(Connection.class);
        transaction = mock(Statement.class);
        schema = mock(PreparedStatement.class);
        table = mock(PreparedStatement.class);
        columns = mock(PreparedStatement.class);
        select = mock(PreparedStatement.class);
        schemaRows = mock(ResultSet.class);
        tableRows = mock(ResultSet.class);
        columnRows = mock(ResultSet.class);
        dataRows = mock(ResultSet.class);
        doReturn(connection).when(connections).open(any(), anyInt());
        when(connection.createStatement()).thenReturn(transaction);
        when(connection.prepareStatement(anyString())).thenReturn(schema, table, columns, select);
        when(schema.executeQuery()).thenReturn(schemaRows);
        when(schemaRows.next()).thenReturn(true, false);
        when(schemaRows.getString(1)).thenReturn("fixture");
        when(table.executeQuery()).thenReturn(tableRows);
        when(tableRows.next()).thenReturn(true, false);
        when(tableRows.getString(1)).thenReturn("BASE TABLE");
        when(columns.executeQuery()).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(true, true, false);
        when(columnRows.getString(1)).thenReturn("id", "value");
        when(columnRows.getString(2)).thenReturn("varchar");
        when(columnRows.getString(3)).thenReturn("");
        when(columnRows.getBoolean(4)).thenReturn(false);
        when(select.executeQuery()).thenReturn(dataRows);
        when(dataRows.next()).thenReturn(true, false);
        when(dataRows.getCharacterStream(1)).thenReturn(new StringReader("1"));
        when(dataRows.getCharacterStream(2)).thenReturn(new StringReader("fixture value"));
    }

    @Test
    void generationExpressionIsReducedToBooleanOnServer() throws Exception {
        client.read(config, access, request, () -> {});
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(4)).prepareStatement(sql.capture());
        assertEquals("SELECT COLUMN_NAME, DATA_TYPE, EXTRA, COALESCE(CHAR_LENGTH(GENERATION_EXPRESSION), 0) > 0 "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", sql.getAllValues().get(2));
        verify(columnRows, never()).getString(4);
        verify(columnRows, times(2)).getBoolean(4);
    }

    @Test
    void everyAllowedScalarUsesServerBoundedStringProjection() throws Exception {
        for (String type : List.of("tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "float", "double",
                "date", "datetime", "timestamp", "time", "year")) {
            prepare();
            when(columnRows.getString(2)).thenReturn(type, "text");
            when(dataRows.getCharacterStream(1)).thenReturn(new StringReader("123"));
            var result = client.read(config, access, request, () -> {});
            assertEquals("123", new JsonMapper().readTree(result.output()).get(0).get("id").asString());
            verify(dataRows).getCharacterStream(1);
            verify(dataRows).getCharacterStream(2);
            verify(dataRows, never()).getString(anyInt());
            var sql = ArgumentCaptor.forClass(String.class);
            verify(connection, times(4)).prepareStatement(sql.capture());
            assertTrue(sql.getAllValues().getLast().startsWith("SELECT LEFT(CONVERT(`id` USING utf8mb4), 128),"));
        }
    }

    @Test
    void staleScalarMetadataStillUsesBoundedProjectionAndTruncationFlag() throws Exception {
        when(columnRows.getString(2)).thenReturn("int", "text");
        when(dataRows.getCharacterStream(1)).thenReturn(null);
        var result = client.read(config, access, request, () -> {});
        assertTrue(new JsonMapper().readTree(result.output()).get(0).get("id").isNull());
        prepare();
        when(columnRows.getString(2)).thenReturn("decimal", "text");
        when(dataRows.getCharacterStream(1)).thenReturn(new StringReader("x".repeat(128)));
        when(dataRows.getBoolean(3)).thenReturn(true);
        var changed = client.read(config, access, request, () -> {});
        assertTrue(changed.truncated());
        assertEquals(1, changed.rows());
        verify(connection).rollback();
    }

    @Test
    void earlyCancellationAndTimeoutDoNotRollbackBeforeManualTransactionMode() throws Exception {
        int[] checkpoints = {0};
        assertThrows(CancellationException.class, () -> client.read(config, access, request, () -> {
            if (++checkpoints[0] == 2) throw new CancellationException();
        }));
        verify(connection, never()).rollback();
        verify(connection).close();
        prepare();
        var timed = new MysqlReadClient.Access("fixture", access.tables(), 2, 1024, Duration.ofMillis(50));
        int[] timedChecks = {0};
        assertThrows(SQLTimeoutException.class, () -> client.read(config, timed, request, () -> {
            if (++timedChecks[0] == 2) {
                try { Thread.sleep(75); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        }));
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void rollbackFailureDoesNotMaskTimeoutCancellationOrPrimaryError() throws Exception {
        doThrow(new SQLException("secret rollback endpoint")).when(connection).rollback();
        when(select.executeQuery()).thenThrow(new SQLTimeoutException("secret timeout endpoint"));
        assertEquals("MySQL read time budget exceeded",
                assertThrows(SQLTimeoutException.class, () -> client.read(config, access, request, () -> {})).getMessage());
        prepare();
        doThrow(new SQLException("secret rollback endpoint")).when(connection).rollback();
        var cancellation = new CancellationException("synthetic cancellation");
        when(select.executeQuery()).thenThrow(cancellation);
        assertSame(cancellation, assertThrows(CancellationException.class, () -> client.read(config, access, request, () -> {})));
        assertEquals("MySQL rollback failed", cancellation.getSuppressed()[0].getMessage());
        prepare();
        doThrow(new SQLException("secret rollback endpoint")).when(connection).rollback();
        var primary = new AssertionError("synthetic error");
        when(select.executeQuery()).thenThrow(primary);
        assertSame(primary, assertThrows(AssertionError.class, () -> client.read(config, access, request, () -> {})));
        verify(connection).close();
    }

    @Test
    void rollbackFailureAfterSuccessfulReadStillFailsClosed() throws Exception {
        doThrow(new SQLException("secret endpoint")).when(connection).rollback();
        assertEquals("MySQL read failed", assertThrows(SQLException.class,
                () -> client.read(config, access, request, () -> {})).getMessage());
        verify(connection).close();
    }

    @Test
    void executesOnlyFixedParameterizedSelectInsideServerReadOnlyTransaction() throws Exception {
        String predicate = "' OR 1=1; SELECT LOAD_FILE('/etc/passwd') --";
        var result = client.read(config, access, request(predicate), () -> {});
        assertEquals(1, result.rows());
        assertFalse(result.truncated());
        assertEquals("fixture value", new JsonMapper().readTree(result.output()).get(0).get("value").asString());
        var order = inOrder(connection, transaction, select);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setAutoCommit(false);
        order.verify(transaction).execute("START TRANSACTION READ ONLY");
        order.verify(select).executeQuery();
        order.verify(connection).rollback();
        order.verify(connection).close();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(4)).prepareStatement(sql.capture());
        assertEquals("SELECT LEFT(CONVERT(`id` USING utf8mb4), 128), LEFT(CONVERT(`value` USING utf8mb4), 128), "
                + "CHAR_LENGTH(CONVERT(`id` USING utf8mb4)) > 128, CHAR_LENGTH(CONVERT(`value` USING utf8mb4)) > 128 "
                + "FROM `fixture`.`records` WHERE `id` = ? LIMIT ?", sql.getAllValues().getLast());
        assertTrue(sql.getAllValues().stream().noneMatch(value -> value.contains(predicate)));
        verify(schema).setString(1, "fixture");
        verify(table).setString(1, "fixture");
        verify(table).setString(2, "records");
        verify(columns).setString(2, "records");
        verify(select).setString(1, predicate);
        verify(select).setInt(2, 3);
        verify(select).setMaxRows(3);
        verify(select).setFetchSize(Integer.MIN_VALUE);
        verify(connection, never()).commit();
        for (Statement statement : List.of(transaction, schema, table, columns, select)) {
            verify(statement).setQueryTimeout(intThat(value -> value >= 1 && value <= 2));
            verify(statement).close();
        }
        for (ResultSet rows : List.of(schemaRows, tableRows, columnRows, dataRows)) verify(rows).close();
        verify(connection, times(5)).setNetworkTimeout(any(), intThat(value -> value > 0 && value <= 2000));
    }

    @Test
    void rejectsUnapprovedTablesColumnsPredicatesLimitsAndSchemasBeforeOpening() throws Exception {
        for (var denied : List.of(new MysqlReadClient.Request("other", List.of("id"), Map.of(), 1),
                new MysqlReadClient.Request("records", List.of("secret"), Map.of(), 1),
                new MysqlReadClient.Request("records", List.of("id"), Map.of("secret", "x"), 1),
                new MysqlReadClient.Request("records", List.of("id"), Map.of(), 3))) {
            assertThrows(IllegalArgumentException.class, () -> client.read(config, access, denied, () -> {}));
        }
        var other = new MysqlClient.Config("localhost", 3306, "other", "reader", "synthetic", "DISABLED");
        assertThrows(IllegalArgumentException.class, () -> client.read(other, access, request, () -> {}));
        verify(connections, never()).open(any(), anyInt());
    }

    @Test
    void rejectsSqlExpressionsFilesWildcardsAndMultipleStatementsAsIdentifiers() {
        for (String identifier : List.of("*", "a.b", "id`", "LOAD_FILE('x')", "SLEEP(5)", "id;DROP TABLE records",
                "records INTO OUTFILE 'x'", "/tmp/file", "../records", "id -- comment", "a".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Request(identifier, List.of("id"), Map.of(), 1));
            assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Request("records", List.of(identifier), Map.of(), 1));
            assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Request("records", List.of("id"), Map.of(identifier, "x"), 1));
        }
        for (String schema : List.of("mysql", "SYS", "information_schema", "performance_schema")) {
            assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Access(schema,
                    Map.of("records", Set.of("id")), 1, 128, Duration.ofSeconds(1)));
        }
    }

    @Test
    void rejectsMissingSchemaMissingTableAndViewsBeforeReadingRows() throws Exception {
        when(schemaRows.next()).thenReturn(false);
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
        verify(connection).rollback();
        verify(connection).close();
        for (String kind : List.of("VIEW", "SYSTEM VIEW", "FOREIGN")) {
            prepare();
            when(tableRows.getString(1)).thenReturn(kind);
            assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
            verifyNoInteractions(select);
            verify(tableRows).close();
            verify(connection).close();
        }
        prepare();
        when(tableRows.next()).thenReturn(false);
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
    }

    @Test
    void rejectsUnknownGeneratedAndUnsupportedColumnsBeforeReadingRows() throws Exception {
        when(columnRows.getString(1)).thenReturn("unapproved");
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
        prepare();
        when(columnRows.getString(3)).thenReturn("STORED GENERATED");
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
        prepare();
        when(columnRows.getBoolean(4)).thenReturn(true);
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
        prepare();
        when(columnRows.getString(2)).thenReturn("blob");
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(select);
    }

    @Test
    void rowLimitUsesOneSentinelAndDisclosesTruncation() throws Exception {
        when(dataRows.next()).thenReturn(true, true, true, false);
        when(dataRows.getCharacterStream(1)).thenAnswer(invocation -> new StringReader("1"));
        when(dataRows.getCharacterStream(2)).thenAnswer(invocation -> new StringReader("value"));
        var result = client.read(config, access, request, () -> {});
        assertEquals(2, result.rows());
        assertTrue(result.truncated());
        assertTrue(result.output().endsWith("[TRUNCATED]\n"));
        verify(dataRows, times(3)).next();
        verify(dataRows, times(2)).getCharacterStream(1);
    }

    @Test
    void serverTruncationFlagPreservesCompleteRowCountAndDisclosesPartialCells() throws Exception {
        when(dataRows.getBoolean(4)).thenReturn(true);
        var result = client.read(config, access, request, () -> {});
        assertEquals(1, result.rows());
        assertTrue(result.truncated());
        assertTrue(result.output().endsWith("]\n[TRUNCATED]\n"));
        assertEquals("fixture value", new JsonMapper().readTree(result.output().split("\\n")[0]).get(0).get("value").asString());
        verify(dataRows).getBoolean(3);
        verify(dataRows).getBoolean(4);
    }

    @Test
    void boundedProjectionNeverChangesOriginalEqualityPredicate() throws Exception {
        when(columnRows.getString(2)).thenReturn("int", "longtext");
        var exact = new MysqlReadClient.Request("records", List.of("id", "value"), Map.of("value", "x".repeat(512)), 1);
        client.read(config, access(2, 64), exact, () -> {});
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(4)).prepareStatement(sql.capture());
        assertEquals("SELECT LEFT(CONVERT(`id` USING utf8mb4), 8), LEFT(CONVERT(`value` USING utf8mb4), 8), "
                + "CHAR_LENGTH(CONVERT(`id` USING utf8mb4)) > 8, CHAR_LENGTH(CONVERT(`value` USING utf8mb4)) > 8 "
                + "FROM `fixture`.`records` WHERE `value` = ? LIMIT ?", sql.getAllValues().getLast());
        verify(select).setString(1, "x".repeat(512));
    }

    @Test
    void exactBoundaryAndNullTextAreNotFalselyTruncated() throws Exception {
        when(dataRows.getCharacterStream(1)).thenReturn(null);
        when(dataRows.getCharacterStream(2)).thenReturn(new StringReader("x".repeat(128)));
        when(dataRows.getBoolean(anyInt())).thenReturn(false);
        var result = client.read(config, access, request, () -> {});
        assertEquals(1, result.rows());
        assertFalse(result.truncated());
        assertTrue(new JsonMapper().readTree(result.output()).get(0).get("id").isNull());
    }

    @Test
    void utf8ByteBudgetBoundsStreamingCellsWithoutMaterializingLargeStrings() throws Exception {
        Reader reader = spy(new StringReader("가".repeat(10000)));
        when(dataRows.getCharacterStream(2)).thenReturn(reader);
        var result = client.read(config, access(2, 64), request, () -> {});
        assertTrue(result.truncated());
        assertTrue(result.output().getBytes(StandardCharsets.UTF_8).length <= 64);
        assertTrue(result.output().endsWith("[TRUNCATED]\n"));
        verify(reader, atMost(20)).read();
        verify(reader).close();
        verify(dataRows, never()).getString(anyInt());
        verify(connection).close();
    }

    @Test
    void outputEscapesControlCharactersQuotesAndSurrogatesAndSupportsNull() throws Exception {
        String value = "\"line\n\\\u0001\ud83d\ude00";
        when(dataRows.getCharacterStream(1)).thenReturn(null);
        when(dataRows.getCharacterStream(2)).thenReturn(new StringReader(value));
        var result = client.read(config, access, request, () -> {});
        var row = new JsonMapper().readTree(result.output()).get(0);
        assertTrue(row.get("id").isNull());
        assertEquals(value, row.get("value").asString());
    }

    @Test
    void emptyQueryHasNoOutput() throws Exception {
        when(dataRows.next()).thenReturn(false);
        var result = client.read(config, access, request, () -> {});
        assertEquals("", result.output());
        assertEquals(0, result.rows());
        assertFalse(result.truncated());
    }

    @Test
    void cancellationBeforeOpenAndDuringReadClosesEverything() throws Exception {
        assertThrows(CancellationException.class, () -> client.read(config, access, request, () -> { throw new CancellationException(); }));
        verify(connections, never()).open(any(), anyInt());
        AtomicBoolean cancelled = new AtomicBoolean();
        when(dataRows.next()).thenAnswer(invocation -> { cancelled.set(true); return true; });
        assertThrows(CancellationException.class, () -> client.read(config, access, request,
                () -> { if (cancelled.get()) throw new CancellationException(); }));
        verify(dataRows).close();
        verify(select).close();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void timeoutAndDatabaseErrorsAreSanitizedAndResourcesClosed() throws Exception {
        when(select.executeQuery()).thenThrow(new SQLTimeoutException("secret endpoint"));
        var timeout = assertThrows(SQLTimeoutException.class, () -> client.read(config, access, request, () -> {}));
        assertEquals("MySQL read time budget exceeded", timeout.getMessage());
        assertNull(timeout.getCause());
        verify(select).close();
        verify(connection).rollback();
        verify(connection).close();
        prepare();
        when(transaction.execute(anyString())).thenThrow(new SQLException("password=synthetic-secret"));
        var failure = assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        assertEquals("MySQL read failed", failure.getMessage());
        verify(connection).close();
        verifyNoInteractions(select);
    }

    @Test
    void expiredElapsedBudgetStopsBeforeNextStatement() throws Exception {
        var tiny = new MysqlReadClient.Access("fixture", access.tables(), 2, 1024, Duration.ofMillis(20));
        when(transaction.execute(anyString())).thenAnswer(invocation -> { Thread.sleep(40); return true; });
        assertThrows(SQLTimeoutException.class, () -> client.read(config, tiny, request, () -> {}));
        verify(schema, never()).executeQuery();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void connectionPropertiesDisableMultiQueriesFileLoadingAndImplicitKeyRetrieval() throws Exception {
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any(Properties.class))).thenReturn(connection);
            try (var opened = new MysqlClient().open(config, 250)) {
                assertSame(connection, opened);
                var url = ArgumentCaptor.forClass(String.class);
                var properties = ArgumentCaptor.forClass(Properties.class);
                driver.verify(() -> DriverManager.getConnection(url.capture(), properties.capture()));
                assertEquals("jdbc:mysql://localhost:3306/", url.getValue());
                assertFalse(url.getValue().contains(config.password()));
                assertFalse(config.toString().contains(config.password()));
                assertEquals("250", properties.getValue().getProperty("connectTimeout"));
                assertEquals("250", properties.getValue().getProperty("socketTimeout"));
                assertEquals("synthetic-secret", properties.getValue().getProperty("password"));
                for (String option : List.of("allowMultiQueries", "allowLoadLocalInfile", "allowUrlInLocalInfile",
                        "allowPublicKeyRetrieval", "autoDeserialize")) {
                    assertEquals("false", properties.getValue().getProperty(option));
                }
                verify(connection).setReadOnly(true);
            }
        }
        verify(connection).close();
    }

    @Test
    void invalidCredentialsAndNullPredicatesCannotOpenAConnection() throws Exception {
        assertThrows(dev.dougle.library.application.LibraryException.class,
                () -> client.read(null, access, request, () -> {}));
        var invalid = new MysqlClient.Config("localhost", 3306, "fixture", "", "synthetic", "DISABLED");
        assertThrows(dev.dougle.library.application.LibraryException.class,
                () -> client.read(invalid, access, request, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Request("records", List.of("id"),
                java.util.Collections.singletonMap("id", null), 1));
        verify(connections, never()).open(any(), anyInt());
    }

    @Test
    void connectionFailureAndReadOnlySetupFailureNeverExposeCredentials() throws Exception {
        doThrow(new SQLException("localhost password=synthetic-secret")).when(connections).open(any(), anyInt());
        var failure = assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        assertEquals("MySQL read failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        prepare();
        doThrow(new SQLException("synthetic-secret")).when(connection).setReadOnly(true);
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verifyNoInteractions(transaction, schema, table, columns, select);
        verify(connection).close();
    }

    @Test
    void readerFailureAndRollbackFailureCloseResourcesAndDiscardPartialRows() throws Exception {
        Reader reader = mock(Reader.class);
        when(reader.read()).thenThrow(new java.io.IOException("private data"));
        when(dataRows.getCharacterStream(2)).thenReturn(reader);
        var failure = assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        assertEquals("MySQL read failed", failure.getMessage());
        verify(reader).close();
        verify(dataRows).close();
        verify(select).close();
        verify(connection).rollback();
        verify(connection).close();
        prepare();
        doThrow(new SQLException("private rollback detail")).when(connection).rollback();
        assertThrows(SQLException.class, () -> client.read(config, access, request, () -> {}));
        verify(connection).close();
    }

    @Test
    void immutableRequestsHaveOrderIndependentPredicateKeysAndRedactedDiagnostics() {
        var predicates = new LinkedHashMap<String, String>();
        predicates.put("value", "private");
        predicates.put("id", "1");
        var first = new MysqlReadClient.Request("records", List.of("id"), predicates, 1);
        var second = new MysqlReadClient.Request("records", List.of("id"), Map.of("id", "1", "value", "private"), 1);
        predicates.clear();
        assertEquals(first.key(), second.key());
        assertEquals(64, first.key().length());
        assertFalse(first.key().contains("private"));
        assertNotEquals(request("one").key(), request("two").key());
        assertFalse(first.toString().contains("private"));
        assertThrows(UnsupportedOperationException.class, () -> first.equalities().clear());
        assertThrows(IllegalArgumentException.class, () -> request("x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> new MysqlReadClient.Request("records", List.of("id", "id"), Map.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> access(201, 1024));
        assertThrows(IllegalArgumentException.class, () -> access(1, 65537));
    }

    private static MysqlReadClient.Access access(int rows, int bytes) {
        return new MysqlReadClient.Access("fixture", Map.of("records", Set.of("id", "value")), rows, bytes, Duration.ofSeconds(2));
    }

    private static MysqlReadClient.Request request(String value) {
        return new MysqlReadClient.Request("records", List.of("id", "value"), Map.of("id", value), 2);
    }
}
