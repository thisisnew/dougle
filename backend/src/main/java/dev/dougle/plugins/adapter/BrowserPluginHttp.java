package dev.dougle.plugins.adapter;

import dev.dougle.library.application.LibraryException;
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
    private final java.util.function.UnaryOperator<URI> transport;
    private final long deadlineMillis;
    public BrowserPluginHttp() { this(java.util.function.UnaryOperator.identity()); }
    BrowserPluginHttp(java.util.function.UnaryOperator<URI> transport) { this(transport, 30_000); }
    BrowserPluginHttp(java.util.function.UnaryOperator<URI> transport, long deadlineMillis) { this.transport = transport; this.deadlineMillis = deadlineMillis; }
    private final JsonMapper json = new JsonMapper();
    private final org.apache.hc.client5.http.impl.classic.CloseableHttpClient client = HttpClients.custom()
            .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(20)).build()).build();
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    public record Reply(JsonNode body, String session) {}

    public Reply send(BrowserProvider provider, String method, URI uri, String body, boolean form, String token, String session, String requestId) {
        validateReadOrAuthentication(provider, method, uri, body, form, token, session, requestId);
        var request = new HttpUriRequestBase(method, transport.apply(uri));
        request.setHeader("Accept", "application/json, text/event-stream");
        if (token != null) request.setHeader("Authorization", "Bearer " + token);
        if (session != null) request.setHeader("Mcp-Session-Id", session);
        request.setHeader("MCP-Protocol-Version", "2025-03-26");
        if (body != null) request.setEntity(requestEntity(body, form));
        var deadline = deadlines.schedule(request::cancel, deadlineMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
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
                            case "invalid_client" -> "앱 등록을 확인하지 못했습니다. Dougle 지원팀에 문의해 주세요. 토큰 발급이나 개발자 설정은 필요하지 않습니다.";
                            case "invalid_scope" -> "Jira · Confluence 읽기 승인을 확인하지 못했습니다. 앱 업데이트와 회사의 앱 연결 정책을 확인해 주세요.";
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
        catch (Exception exception) { throw transportFailure(provider, exception, request.isCancelled()); }
        finally { deadline.cancel(false); }
    }

    static LibraryException transportFailure(BrowserProvider provider, Exception failure, boolean cancelled) {
        String host = URI.create(provider.origin).getHost();
        if (cancelled) return new LibraryException(504, "서비스 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.");
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof tools.jackson.core.JacksonException)
                return new LibraryException(502, "서비스 응답 형식이 올바르지 않습니다. 잠시 후 다시 시도해 주세요.");
            if (cause instanceof javax.net.ssl.SSLException)
                return new LibraryException(502, "서비스 보안 연결을 확인하지 못했습니다. " + host + " 인증서·TLS 정책을 확인해 주세요.");
            if (cause instanceof java.net.UnknownHostException)
                return new LibraryException(502, "서비스 주소를 찾지 못했습니다. " + host + " DNS·네트워크 연결을 확인해 주세요.");
            if (cause instanceof java.io.InterruptedIOException)
                return new LibraryException(504, "서비스 연결 또는 응답 시간이 초과되었습니다. 네트워크를 확인하고 다시 시도해 주세요.");
            if (cause instanceof java.net.ConnectException)
                return new LibraryException(502, "서비스에 접속하지 못했습니다. " + host + " 네트워크·프록시 연결을 확인해 주세요.");
        }
        return new LibraryException(502, "서비스 응답 형식을 확인하지 못했거나 응답이 중단되었습니다. 잠시 후 다시 시도해 주세요.");
    }

    static void validateReadOrAuthentication(BrowserProvider provider, String method, URI uri, String body,
                                             boolean form, String token, String session, String requestId) {
        provider.trusted(uri.toString());
        if ("GET".equals(method) && uri.toString().equals(provider.origin + "/.well-known/oauth-authorization-server")
                && body == null && !form && token == null && session == null && requestId == null) return;
        if (!"POST".equals(method) || body == null || body.length() > 65536) throw readOnlyDenied();
        try {
            if (uri.equals(URI.create(provider.origin + provider.path))) {
                if (form || token == null) throw readOnlyDenied();
                var payload = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(body);
                if (!only(payload, java.util.Set.of("jsonrpc", "id", "method", "params"))
                        || !"2.0".equals(payload.path("jsonrpc").asText()) || !payload.path("params").isObject()) throw readOnlyDenied();
                String operation = payload.path("method").asText();
                var params = payload.path("params");
                switch (operation) {
                    case "initialize" -> {
                        if (!only(params, java.util.Set.of("protocolVersion", "capabilities", "clientInfo"))
                                || !params.path("capabilities").isObject() || !params.path("capabilities").isEmpty()) throw readOnlyDenied();
                    }
                    case "notifications/initialized" -> {
                        if (!params.isEmpty() || payload.has("id") || requestId != null) throw readOnlyDenied();
                    }
                    case "tools/list" -> {
                        if (!only(params, java.util.Set.of("cursor"))) throw readOnlyDenied();
                    }
                    case "tools/call" -> {
                        if (!only(params, java.util.Set.of("name", "arguments"))) throw readOnlyDenied();
                        PluginMcpSession.validateReadArguments(provider, params.path("name").asText(), params.path("arguments"));
                    }
                    default -> throw readOnlyDenied();
                }
                if (!"notifications/initialized".equals(operation)
                        && (requestId == null || !requestId.equals(payload.path("id").asText()))) throw readOnlyDenied();
                return;
            }
            if (token != null || session != null || requestId != null || uri.getRawQuery() != null) throw readOnlyDenied();
            if (form) {
                var fields = BrowserPluginConnections.query(body);
                var allowed = switch (fields.getOrDefault("grant_type", "")) {
                    case "authorization_code" -> java.util.Set.of("grant_type", "client_id", "code", "redirect_uri", "code_verifier", "resource");
                    case "refresh_token" -> java.util.Set.of("grant_type", "client_id", "refresh_token", "resource");
                    default -> throw readOnlyDenied();
                };
                if (!allowed.equals(fields.keySet()) || fields.values().stream().anyMatch(String::isBlank)
                        || !provider.resource().equals(fields.get("resource"))) throw readOnlyDenied();
            } else {
                var registration = JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(body);
                if (!only(registration, java.util.Set.of("client_name", "redirect_uris", "grant_types", "response_types", "token_endpoint_auth_method"))
                        || registration.size() != 5 || !"Dougle".equals(registration.path("client_name").asText())
                        || !"none".equals(registration.path("token_endpoint_auth_method").asText())
                        || !registration.path("grant_types").toString().equals("[\"authorization_code\",\"refresh_token\"]")
                        || !registration.path("response_types").toString().equals("[\"code\"]")
                        || !registration.path("redirect_uris").isArray() || registration.path("redirect_uris").size() != 1
                        || !registration.path("redirect_uris").get(0).asText().matches("http://127\\.0\\.0\\.1:[0-9]{1,5}/callback")) throw readOnlyDenied();
            }
        } catch (LibraryException denied) { throw denied; }
        catch (RuntimeException invalid) { throw readOnlyDenied(); }
    }

    private static boolean only(JsonNode value, java.util.Set<String> names) {
        return value.isObject() && value.size() == names.stream().filter(value::has).count();
    }

    static LibraryException readOnlyDenied() { return new LibraryException(403, "외부 서비스는 등록된 읽기 기능과 인증 연결만 허용합니다."); }

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
