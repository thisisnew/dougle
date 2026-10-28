package dev.dougle.plugins.adapter;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Map;
import dev.dougle.library.application.LibraryException;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

class BrowserPluginHttpTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "FORGE_VERIFY_NOTION_FORM", matches = "true")
    void notionParsesSyntheticFormBeforeRejectingUnknownClient() throws Exception {
        var http = new BrowserPluginHttp();
        try {
            String body = BrowserPluginHttp.form(Map.of("grant_type", "authorization_code", "client_id", "dougle-synthetic-invalid-client",
                    "code", "synthetic-invalid-code", "redirect_uri", "http://127.0.0.1:12345/callback",
                    "code_verifier", "a".repeat(43), "resource", BrowserProvider.NOTION.resource()));
            assertThatThrownBy(() -> http.send(BrowserProvider.NOTION, "POST", URI.create("https://mcp.notion.com/token"), body, true, null, null, null))
                    .isInstanceOfSatisfying(LibraryException.class, exception -> assertThat(exception.status()).isEqualTo(401));
        } finally { http.close(); }
    }

    @Test void oauthFormsUseUtf8ForCodeExchangeAndRefresh() throws Exception {
        for (String grant : new String[] { "authorization_code", "refresh_token" }) {
            var fields = Map.of("grant_type", grant, "code", "synthetic+code/=", "client_id", "가상 앱",
                    "redirect_uri", "http://127.0.0.1:12345/callback", "resource", "https://mcp.notion.com/mcp");
            try (var entity = BrowserPluginHttp.requestEntity(BrowserPluginHttp.form(fields), true)) {
                var type = ContentType.parse(entity.getContentType());
                assertThat(type.getMimeType()).isEqualTo("application/x-www-form-urlencoded");
                assertThat(type.getCharset()).isEqualTo(StandardCharsets.UTF_8);
                assertThat(BrowserPluginConnections.query(new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8))).isEqualTo(fields);
            }
        }
    }

    @Test void jsonRequestsAlsoPreserveUnicode() throws Exception {
        String body = "{\"name\":\"가상 문서🙂\"}";
        try (var entity = BrowserPluginHttp.requestEntity(body, false)) {
            assertThat(ContentType.parse(entity.getContentType()).getMimeType()).isEqualTo("application/json");
            assertThat(new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
    }
}
