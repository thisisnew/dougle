package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginConnectionsTest {
    @TempDir Path root;
    private final String token = "ntn_synthetic_test_token_not_real_12345";
    private MysqlClient.Config config() { return new MysqlClient.Config("localhost", 3306, "fixture", "reader", "synthetic-password", "DISABLED"); }

    @Test void everySavedConnectionAndDisconnectRotateThePersistedMysqlRevision() {
        var service = new PluginConnections(new PluginVault(root.toString()), mock(NotionClient.class), mock(MysqlClient.class));
        service.mysql(config(), true);
        String first = service.mysqlRevision();
        service.mysql(config(), false);
        assertThat(service.mysqlRevision()).isEqualTo(first);
        service.disconnect("mysql");
        String disconnected = service.mysqlRevision();
        assertThat(disconnected).isNotEqualTo(first);
        service.mysql(config(), true);
        assertThat(service.mysqlRevision()).isNotEqualTo(first).isNotEqualTo(disconnected);
        var restarted = new PluginConnections(new PluginVault(root.toString()), mock(NotionClient.class), mock(MysqlClient.class));
        assertThat(restarted.mysqlRevision()).isEqualTo(service.mysqlRevision());
    }

    @Test void vaultPersistsEncryptedCredentialsAndDisconnectRemovesThem() throws Exception {
        var vault = new PluginVault(root.toString());
        var service = new PluginConnections(vault, mock(NotionClient.class), mock(MysqlClient.class));
        service.notion(token, true);
        service.mysql(config(), true);
        assertThat(Files.readString(root.resolve("plugin-secrets/key"), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(token);
        assertThat(new String(Files.readAllBytes(root.resolve("plugin-secrets/credentials.enc")), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(token, "synthetic-password");
        assertThat(Files.getPosixFilePermissions(root.resolve("plugin-secrets/credentials.enc"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var restarted = new PluginConnections(new PluginVault(root.toString()), mock(NotionClient.class), mock(MysqlClient.class));
        assertThat(restarted.notionToken()).isEqualTo(token);
        assertThat(new JsonMapper().writeValueAsString(restarted.status())).doesNotContain(token, "synthetic-password");
        restarted.disconnect("notion");
        assertThat(restarted.status().notionConnected()).isFalse();
        assertThat(restarted.status().mysqlConnected()).isTrue();
    }

    @Test void failedAndTestOnlyConnectionsNeverOverwriteSavedCredentials() {
        var notion = mock(NotionClient.class);
        var service = new PluginConnections(new PluginVault(root.toString()), notion, mock(MysqlClient.class));
        service.notion(token, false);
        assertThat(service.status().notionConnected()).isFalse();
        service.notion(token, true);
        doThrow(new LibraryException(400, "거절됨")).when(notion).test("invalid");
        assertThatThrownBy(() -> service.notion("invalid", true)).isInstanceOf(LibraryException.class);
        assertThat(service.notionToken()).isEqualTo(token);
    }

    @Test void vaultRejectsSymlinkAndTamperedCiphertext() throws Exception {
        var vault = new PluginVault(root.toString());
        vault.put("notion", token);
        Files.write(root.resolve("plugin-secrets/credentials.enc"), new byte[40]);
        assertThatThrownBy(() -> vault.get("notion")).isInstanceOf(LibraryException.class);
        Files.delete(root.resolve("plugin-secrets/credentials.enc"));
        Path outside = Files.writeString(root.resolve("outside"), "unchanged");
        Files.createSymbolicLink(root.resolve("plugin-secrets/credentials.enc"), outside);
        assertThatThrownBy(() -> vault.put("notion", token)).isInstanceOf(LibraryException.class);
        assertThat(Files.readString(outside)).isEqualTo("unchanged");
    }

    @Test void notionExtractsTitleNestedBlocksAndPagination() throws Exception {
        var notion = spy(new NotionClient());
        var json = new JsonMapper();
        String id = "359fdb093f9380648576c536ea909b2d";
        try {
            doReturn(json.readTree("{\"properties\":{\"title\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Synthetic Agile\"}]}}}")).when(notion).get("pages/" + id, token);
            doReturn(json.readTree("{\"results\":[{\"id\":\"child\",\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Parent\"}]},\"has_children\":true}],\"has_more\":true,\"next_cursor\":\"next\"}")).when(notion).get("blocks/" + id + "/children?page_size=100", token);
            doReturn(json.readTree("{\"results\":[{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Nested evidence\"}]}}],\"has_more\":false}")).when(notion).get("blocks/child/children?page_size=100", token);
            doReturn(json.readTree("{\"results\":[{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Second page\"}]}}],\"has_more\":false}")).when(notion).get("blocks/" + id + "/children?page_size=100&start_cursor=next", token);
            var snapshot = notion.collect("https://app.notion.com/p/Agile-" + id, token);
            assertThat(new String(snapshot.files().getFirst().content(), java.nio.charset.StandardCharsets.UTF_8)).contains("Synthetic Agile", "Parent", "Nested evidence", "Second page");
        } finally { notion.close(); }
    }

    @Test void notionMissingTokenAndInvalidLinksFailWithoutNetwork() throws Exception {
        var notion = spy(new NotionClient());
        try {
            assertThatThrownBy(() -> notion.collect("https://app.notion.com/p/synthetic", null)).hasMessageContaining("연결이 필요");
            assertThatThrownBy(() -> notion.collect("https://app.notion.com/p/synthetic", token)).hasMessageContaining("페이지 링크");
            verify(notion, never()).get(anyString(), anyString());
        } finally { notion.close(); }
    }

    @Test void mysqlRejectsJdbcInjectionAndRemotePlaintext() {
        var client = new MysqlClient();
        for (String host : List.of("localhost?allowLoadLocalInfile=true", "host:3306", "user@host", "host/path")) {
            assertThatThrownBy(() -> client.validate(new MysqlClient.Config(host, 3306, "fixture", "reader", "secret", "VERIFY_IDENTITY"))).isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> client.validate(new MysqlClient.Config("example.org", 3306, "fixture", "reader", "secret", "DISABLED"))).hasMessageContaining("localhost");
        assertThat(config().toString()).doesNotContain("synthetic-password");
    }

    @Test void mysqlReadsOnlySchemaWithBoundDatabaseAndClosesConnection() throws Exception {
        var client = spy(new MysqlClient());
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        doReturn(connection).when(client).open(config());
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("fixture_table");
        when(rows.getString(2)).thenReturn("fixture_column");
        var snapshot = client.schema(config());
        assertThat(new String(snapshot.files().getFirst().content())).contains("fixture_table", "fixture_column");
        verify(query, times(2)).setString(1, "fixture");
        verify(query, times(2)).setQueryTimeout(10);
        verify(connection).close();
        verify(connection, never()).createStatement();
    }
}
