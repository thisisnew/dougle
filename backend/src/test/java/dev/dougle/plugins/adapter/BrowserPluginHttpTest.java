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
    @Test void malformedJsonOverLocalHttpDoesNotBlameCompanyNetworkOrExposePayload() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "{synthetic-secret-invalid-json".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var http = new BrowserPluginHttp(uri -> URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        try {
            assertThatThrownBy(() -> discovery(http)).isInstanceOfSatisfying(LibraryException.class, exception -> {
                assertThat(exception.status()).isEqualTo(502);
                assertThat(exception.getMessage()).contains("응답 형식").doesNotContain("synthetic-secret", "VPN", "프록시", "인증서", "관리자");
            });
        } finally { http.close(); server.stop(0); }
    }

    @Test void localHttpDeadlineIsReportedAsTimeout() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var http = new BrowserPluginHttp(uri -> URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), 250);
        try {
            assertThatThrownBy(() -> discovery(http)).isInstanceOfSatisfying(LibraryException.class, exception -> {
                assertThat(exception.status()).isEqualTo(504);
                assertThat(exception.getMessage()).contains("시간이 초과").doesNotContain("인증서", "관리자");
            });
        } finally { release.countDown(); http.close(); server.stop(0); }
    }

    @Test void oversizedLocalHttpResponsePreservesSizeError() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = new byte[10 * 1024 * 1024 + 1];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        });
        server.start();
        var http = new BrowserPluginHttp(uri -> URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        try {
            assertThatThrownBy(() -> discovery(http)).isInstanceOfSatisfying(LibraryException.class, exception -> {
                assertThat(exception.status()).isEqualTo(413);
                assertThat(exception.getMessage()).contains("너무 큽니다").doesNotContain("관리자");
            });
        } finally { http.close(); server.stop(0); }
    }

    @Test void classifiedTransportFailuresUseBoundedMessagesWithoutExceptionText() {
        Map<Exception, String> failures = Map.of(
                new javax.net.ssl.SSLHandshakeException("synthetic-secret"), "인증서·TLS",
                new java.net.UnknownHostException("synthetic-secret"), "DNS",
                new java.net.SocketTimeoutException("synthetic-secret"), "시간이 초과",
                new java.net.ConnectException("synthetic-secret"), "접속하지 못했습니다");
        failures.forEach((failure, expected) -> {
            var result = BrowserPluginHttp.transportFailure(BrowserProvider.ATLASSIAN, new java.io.IOException("synthetic-secret", failure), false);
            assertThat(result.getMessage()).contains(expected).doesNotContain("synthetic-secret");
        });
    }

    private void discovery(BrowserPluginHttp http) {
        http.send(BrowserProvider.ATLASSIAN, "GET", URI.create(BrowserProvider.ATLASSIAN.origin + "/.well-known/oauth-authorization-server"),
                null, false, null, null, null);
    }

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
