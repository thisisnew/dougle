package dev.knowledgeforge.plugins.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BrowserPluginHttp {
    private final JsonMapper json = new JsonMapper();
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient client = HttpClients.custom()
            .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(20)).build()).build();
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    public record Reply(JsonNode body, String session) {}

    public Reply send(BrowserProvider provider, String method, URI uri, String body, boolean form, String token, String session, String requestId) {
        provider.trusted(uri.toString());
        var request = new HttpUriRequestBase(method, uri);
        request.setHeader("Accept", "application/json, text/event-stream");
        if (token != null) request.setHeader("Authorization", "Bearer " + token);
        if (session != null) request.setHeader("Mcp-Session-Id", session);
        request.setHeader("MCP-Protocol-Version", "2025-03-26");
        if (body != null) request.setEntity(requestEntity(body, form));
        var deadline = deadlines.schedule(request::cancel, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status == 401) throw new LibraryException(401, provider.label + " 연결이 필요합니다. 다시 연결해 주세요.");
                if (status == 403) throw new LibraryException(403, "접근이 허용되지 않았습니다. 회사 관리자에게 앱 연결 권한을 확인해 주세요.");
                if (status == 429) throw new LibraryException(429, "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
                if (status < 200 || status >= 300) {
                    if (form && response.getEntity() != null) {
                        var error = json.readTree(readBounded(response.getEntity().getContent(), 65536));
                        if ("invalid_grant".equals(error.path("error").asText())) throw new LibraryException(401, "인증이 만료되었습니다. 다시 연결해 주세요.");
                        String reason = switch (error.path("error").asText()) {
                            case "invalid_target" -> "인증 대상이 일치하지 않습니다. 앱 업데이트 후 다시 연결해 주세요.";
                            case "invalid_client" -> "앱 등록을 확인하지 못했습니다. 다시 연결해 주세요.";
                            case "invalid_request", "unsupported_grant_type" -> "서비스에서 인증 요청을 거부했습니다. 앱 업데이트를 확인해 주세요.";
                            default -> null;
                        };
                        if (reason != null) throw new LibraryException(502, reason + " (HTTP " + status + ")");
                    }
                    throw new LibraryException(502, "서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. (HTTP " + status + ")");
                }
                String nextSession = response.getFirstHeader("Mcp-Session-Id") == null ? session : response.getFirstHeader("Mcp-Session-Id").getValue();
                if (nextSession != null && !nextSession.matches("[\\x21-\\x7e]{1,512}")) throw new LibraryException(502, "잘못된 연결 응답입니다.");
                if (status == 202 || status == 204 || response.getEntity() == null) return new Reply(json.createObjectNode(), nextSession);
                try (var input = response.getEntity().getContent()) {
                    String type = response.getEntity().getContentType();
                    JsonNode result = type != null && type.startsWith("text/event-stream")
                            ? readEvent(input, requestId) : json.readTree(readBounded(input, 10 * 1024 * 1024));
                    return new Reply(result, nextSession);
                }
            });
        } catch (LibraryException exception) { throw exception; }
        catch (Exception exception) { throw new LibraryException(502, "서비스 응답을 확인하지 못했습니다. 네트워크를 확인해 주세요."); }
        finally { deadline.cancel(false); }
    }

    static StringEntity requestEntity(String body, boolean form) {
        return new StringEntity(body, (form ? ContentType.APPLICATION_FORM_URLENCODED : ContentType.APPLICATION_JSON).withCharset(StandardCharsets.UTF_8));
    }

    JsonNode readEvent(InputStream input, String requestId) throws IOException {
        var line = new StringBuilder();
        var event = new StringBuilder();
        for (int count = 0; count < 10 * 1024 * 1024; count++) {
            int next = input.read();
            if (next == -1) break;
            if (next != '\n') { line.append((char) next); continue; }
            String value = line.toString().replaceFirst("\r$", ""); line.setLength(0);
            if (value.startsWith("data:")) event.append(value.substring(5).stripLeading()).append('\n');
            if (value.isEmpty() && !event.isEmpty()) {
                JsonNode parsed = json.readTree(event.toString().getBytes(StandardCharsets.ISO_8859_1));
                event.setLength(0);
                if (requestId != null && requestId.equals(parsed.path("id").asText())) return parsed;
            }
        }
        throw new LibraryException(502, "서비스 응답을 끝까지 읽지 못했습니다.");
    }
    private byte[] readBounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new LibraryException(413, "자료가 너무 큽니다. 작은 단위로 추가해 주세요.");
        return bytes;
    }
    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @PreDestroy public void close() throws Exception { deadlines.shutdownNow(); client.close(); }
}
