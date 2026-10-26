package dev.knowledgeforge.plugins.adapter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlReadOnlyConnectionTest {
    private final MysqlClient.Config config = new MysqlClient.Config("localhost", 3306, "fixture", "synthetic-reader", "synthetic-secret", "DISABLED");

    @Test void connectionDisablesMultiStatementsFilesDeserializationAndRequestsReadOnly() throws Exception {
        var connection = mock(Connection.class);
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any(Properties.class))).thenAnswer(call -> {
                assertThat((String) call.getArgument(0)).isEqualTo("jdbc:mysql://localhost:3306/");
                Properties properties = call.getArgument(1);
                for (String name : java.util.List.of("allowMultiQueries", "allowLoadLocalInfile", "allowUrlInLocalInfile", "allowPublicKeyRetrieval", "autoDeserialize")) {
                    assertThat(properties.getProperty(name)).isEqualTo("false");
                }
                assertThat(properties.getProperty("socketTimeout")).isEqualTo("500");
                return connection;
            });
            assertThat(new MysqlClient().open(config, 500)).isSameAs(connection);
            verify(connection).setReadOnly(true);
            verifyNoMoreInteractions(connection);
        }
    }

    @Test void refusingReadOnlyClosesConnectionWithoutExecutingSql() throws Exception {
        var connection = mock(Connection.class);
        doThrow(new SQLException("synthetic refusal")).when(connection).setReadOnly(true);
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), any(Properties.class))).thenReturn(connection);
            assertThatThrownBy(() -> new MysqlClient().open(config)).isInstanceOf(SQLException.class);
            verify(connection).setReadOnly(true);
            verify(connection).close();
            verifyNoMoreInteractions(connection);
        }
    }
}
