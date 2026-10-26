package dev.knowledgeforge.plugins.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class DatadogConnectionsTest {
    @TempDir Path root;
    private final LibraryStore library = mock(LibraryStore.class);
    private final JsonMapper json = new JsonMapper();
    private final DatadogLogsClient.Config config = new DatadogLogsClient.Config(DatadogLogsClient.Site.EU1,
            "synthetic_api_key_0000000000000000", "synthetic_app_key_0000000000000000");
    private DatadogConnections connections;
    private DatadogController controller;

    @BeforeEach
    void prepare() {
        when(library.workspace("one")).thenReturn(Optional.of(new Workspace("one", "fixture", "now")));
        when(library.workspace("two")).thenReturn(Optional.of(new Workspace("two", "fixture2", "now")));
        connections = new DatadogConnections(new PluginVault(root.toString()), library);
        controller = new DatadogController(connections);
    }

    @Test
    void configurationApiStoresEncryptedKeysAndReturnsOnlyUnverifiedStatus() throws Exception {
        assertFalse(controller.status("one").configured());
        var request = request(json.writeValueAsString(config));
        var status = controller.save("one", request);
        assertTrue(status.configured());
        assertEquals(DatadogLogsClient.Site.EU1, status.site());
        assertFalse(json.writeValueAsString(status).contains(config.apiKey()));
        assertFalse(json.writeValueAsString(status).contains(config.applicationKey()));
        assertFalse(config.toString().contains(config.apiKey()));
        String encrypted = new String(Files.readAllBytes(root.resolve("plugin-secrets/credentials.enc")), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(encrypted.contains(config.apiKey()));
        var restarted = new DatadogConnections(new PluginVault(root.toString()), library);
        assertEquals(config, restarted.require("one", status.revision()));
        assertFalse(restarted.status("two").configured());
    }

    @Test
    void rotationDisconnectionAndWorkspaceDeletionInvalidateApprovedRevision() {
        var saved = connections.save("one", config);
        connections.save("one", config);
        assertThrows(LibraryException.class, () -> connections.require("one", saved.revision()));
        var latest = connections.status("one");
        controller.disconnect("one");
        assertFalse(connections.status("one").configured());
        assertThrows(LibraryException.class, () -> connections.require("one", latest.revision()));
        connections.save("one", config);
        when(library.workspace("one")).thenReturn(Optional.empty());
        assertThrows(LibraryException.class, () -> connections.status("one"));
    }

    @Test
    void malformedOversizedAndUnknownConfigurationFieldsAreRejectedWithoutLeakingKeys() {
        String valid = json.writeValueAsString(config);
        for (String body : List.of("null", "{}", valid + "{}", valid.replace("\"site\":\"EU1\"", "\"site\":\"EU1\",\"endpoint\":\"https://evil.invalid\""),
                valid.replace("\"site\":\"EU1\"", "\"site\":0"),
                valid.replace("\"site\":\"EU1\"", "\"site\":\"EU1\",\"site\":\"US1\""), "x".repeat(4097))) {
            var error = assertThrows(LibraryException.class, () -> controller.save("one", request(body)));
            assertFalse(error.getMessage().contains(config.apiKey()));
            assertFalse(connections.status("one").configured());
        }
        assertThrows(IllegalArgumentException.class, () -> new DatadogLogsClient.Config(DatadogLogsClient.Site.US1, "bad\nheader", config.applicationKey()));
    }

    @Test
    void identicalReconnectCannotRestoreOldRevisionAndErrorsAreLocalized() {
        var old = connections.save("one", config);
        connections.disconnect("one");
        var replacement = connections.save("one", config);
        assertNotEquals(old.revision(), replacement.revision());
        var revoked = assertThrows(LibraryException.class, () -> connections.require("one", old.revision()));
        assertEquals("Datadog 연결이 변경되었습니다. 새 조회 승인이 필요합니다.", revoked.getMessage());
        assertEquals(config, connections.require("one", replacement.revision()));
        var invalid = assertThrows(LibraryException.class, () -> controller.save("one", request("{\"providerError\":\"secret\"}")));
        assertEquals("Datadog 연결 설정을 확인하세요.", invalid.getMessage());
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
