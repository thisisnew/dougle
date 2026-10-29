package dev.dougle.plugins.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dougle.library.application.LibraryException;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BrowserPluginConnections {
    public record Progress(String id, String state, String message) {}
    record Client(String id, int port) {}
    record Grant(String access, String refresh, long expires, String tokenEndpoint, String clientId) {
        @Override public String toString() { return "Grant[redacted]"; }
    }
    private static final class Attempt {
        final String id = UUID.randomUUID().toString();
        final String state = random();
        final String verifier = random();
        final long expires;
        Attempt(long lifetime) { expires = System.currentTimeMillis() + lifetime; }
        String phase = "STARTING";
        String message = "브라우저를 여는 중…";
        HttpServer callback;
        Future<?> timeout;
        boolean pending() { return Set.of("STARTING", "WAITING", "VERIFYING").contains(phase); }
        Progress progress() { return new Progress(id, phase, message); }
    }
    private final PluginVault vault;
    private final BrowserPluginHttp http;
    private final PluginBrowser browser;
    private final long lifetime;
    private final JsonMapper json = new JsonMapper();
    private final Map<BrowserProvider, Attempt> attempts = new EnumMap<>(BrowserProvider.class);
    private final Map<BrowserProvider, Object> grantLocks = new EnumMap<>(BrowserProvider.class);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    @org.springframework.beans.factory.annotation.Autowired
    public BrowserPluginConnections(PluginVault vault, BrowserPluginHttp http, PluginBrowser browser) {
        this(vault, http, browser, 300_000);
    }
    BrowserPluginConnections(PluginVault vault, BrowserPluginHttp http, PluginBrowser browser, long lifetime) {
        this.vault = vault; this.http = http; this.browser = browser;
        this.lifetime = lifetime;
        for (var provider : BrowserProvider.values()) grantLocks.put(provider, new Object());
    }
    private static String key(BrowserProvider provider) { return "browser-" + provider.name(); }
    public boolean connected(BrowserProvider provider) { return vault.get(key(provider)) != null; }
    public synchronized Progress start(BrowserProvider provider) {
        var previous = attempts.get(provider);
        if (previous != null && previous.pending()) throw new LibraryException(409, "이미 연결을 진행하고 있습니다.");
        var attempt = new Attempt(lifetime);
        attempts.put(provider, attempt);
        attempt.timeout = timers.schedule(() -> finish(provider, attempt, "EXPIRED", "연결 시간이 지났습니다. 다시 연결해 주세요."), lifetime, TimeUnit.MILLISECONDS);
        workers.submit(() -> prepare(provider, attempt));
        return attempt.progress();
    }
    public synchronized Progress progress(BrowserProvider provider, String id) {
        var attempt = attempts.get(provider);
        if (attempt == null || !attempt.id.equals(id)) throw new LibraryException(404, "연결 요청을 찾을 수 없습니다. 다시 연결해 주세요.");
        return attempt.progress();
    }
    public synchronized void cancel(BrowserProvider provider, String id) {
        var attempt = attempts.get(provider);
        if (attempt != null && attempt.id.equals(id)) finish(provider, attempt, "CANCELLED", "연결을 취소했습니다.");
    }
    private synchronized boolean active(BrowserProvider provider, Attempt attempt) {
        return attempts.get(provider) == attempt && attempt.pending() && System.currentTimeMillis() < attempt.expires;
    }
    private synchronized void finish(BrowserProvider provider, Attempt attempt, String phase, String message) {
        if (attempts.get(provider) != attempt || !attempt.pending()) return;
        attempt.phase = phase; attempt.message = message;
        if (attempt.timeout != null) attempt.timeout.cancel(false);
        if (attempt.callback != null) attempt.callback.stop(0);
    }
    private void prepare(BrowserProvider provider, Attempt attempt) {
        String stage = "서비스 인증 설정 확인";
        try {
            JsonNode metadata = http.send(provider, "GET", URI.create(provider.origin + "/.well-known/oauth-authorization-server"), null, false, null, null, null).body();
            boolean pkce = false;
            if (metadata.path("code_challenge_methods_supported").isArray())
                for (JsonNode method : metadata.path("code_challenge_methods_supported")) if (method.isTextual() && method.asText().equals("S256")) pkce = true;
            if (!provider.origin.equals(metadata.path("issuer").asText()) || !pkce)
                throw new LibraryException(502, "안전한 인증 방식을 확인하지 못했습니다. 앱 업데이트를 확인하거나 Dougle 지원팀에 문의해 주세요.");
            URI authorization = provider.trusted(metadata.path("authorization_endpoint").asText());
            URI token = provider.trusted(metadata.path("token_endpoint").asText());
            if (metadata.path("registration_endpoint").asText("").isBlank())
                throw new LibraryException(503, "서비스에서 자동 연결 등록을 제공하지 않습니다. Dougle 지원팀에 문의해 주세요. 토큰 발급이나 개발자 설정은 필요하지 않습니다.");
            URI registration = provider.trusted(metadata.path("registration_endpoint").asText());
            String saved = vault.get(key(provider) + "-client");
            Client registered = saved == null ? null : json.readValue(saved, Client.class);
            stage = "로컬 인증 응답 준비";
            HttpServer listener;
            try { listener = HttpServer.create(new InetSocketAddress("127.0.0.1", registered == null ? 0 : registered.port()), 4); }
            catch (BindException occupied) {
                if (registered == null) throw occupied;
                registered = null;
                listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            }
            String redirect = "http://127.0.0.1:" + listener.getAddress().getPort() + "/callback";
            synchronized (this) {
                if (!active(provider, attempt)) { listener.stop(0); return; }
                attempt.callback = listener;
            }
            if (registered == null) {
                stage = "앱 자동 연결 등록";
                var request = Map.of("client_name", "Dougle", "redirect_uris", List.of(redirect),
                        "grant_types", List.of("authorization_code", "refresh_token"), "response_types", List.of("code"), "token_endpoint_auth_method", "none");
                JsonNode result = http.send(provider, "POST", registration, json.writeValueAsString(request), false, null, null, null).body();
                String clientId = credential(result, "client_id", true);
                if (!"none".equals(result.path("token_endpoint_auth_method").asText("none"))) throw new LibraryException(502, "브라우저 인증을 지원하지 않는 응답입니다.");
                registered = new Client(clientId, listener.getAddress().getPort());
                synchronized (this) {
                    if (!active(provider, attempt)) return;
                    vault.put(key(provider) + "-client", json.writeValueAsString(registered));
                }
            }
            Client client = registered;
            listener.createContext("/callback", exchange -> callback(provider, attempt, client, token, redirect, exchange));
            listener.setExecutor(workers);
            synchronized (this) {
                if (!active(provider, attempt)) return;
                listener.start();
                attempt.phase = "WAITING"; attempt.message = "브라우저에서 연결을 완료해 주세요.";
                var params = new LinkedHashMap<String, String>();
                params.put("client_id", client.id()); params.put("response_type", "code"); params.put("redirect_uri", redirect);
                params.put("state", attempt.state); params.put("code_challenge", challenge(attempt.verifier)); params.put("code_challenge_method", "S256");
                params.put("resource", provider.resource());
                if (provider == BrowserProvider.ATLASSIAN) params.put("scope", "read:jira:agent-interface read:confluence:agent-interface");
                stage = "기본 브라우저 열기";
                browser.open(provider, URI.create(authorization + "?" + BrowserPluginHttp.form(params)));
            }
        } catch (Exception exception) {
            finish(provider, attempt, "FAILED", stage + ": " + (exception instanceof LibraryException ? exception.getMessage() : "인증을 시작하지 못했습니다. 앱을 다시 열고 연결해 주세요."));
        }
    }
    private void callback(BrowserProvider provider, Attempt attempt, Client client, URI endpoint, String redirect, HttpExchange exchange) {
        boolean accepted = false;
        String stage = "인증 응답 확인";
        try {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/callback".equals(exchange.getRequestURI().getPath())
                    || !URI.create(redirect).getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) { respond(exchange, 400, "잘못된 요청입니다."); return; }
            Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
            synchronized (this) {
                if (!active(provider, attempt) || !"WAITING".equals(attempt.phase)
                        || !MessageDigest.isEqual(attempt.state.getBytes(StandardCharsets.UTF_8), params.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))
                        || (params.containsKey("iss") && !provider.origin.equals(params.get("iss")))) { respond(exchange, 400, "유효하지 않거나 만료된 연결입니다."); return; }
                accepted = true;
                attempt.phase = "VERIFYING"; attempt.message = "연결을 확인하는 중…";
            }
            if (params.containsKey("error")) {
                respond(exchange, 200, "연결이 승인되지 않았습니다. Dougle에서 다시 연결할 수 있습니다.");
                finish(provider, attempt, "CANCELLED", "연결이 승인되지 않았습니다. 필요하면 회사 관리자에게 권한을 확인해 주세요."); return;
            }
            String code = params.get("code");
            if (code == null || code.isBlank() || code.length() > 4096) throw new LibraryException(400, "인증 응답을 확인하지 못했습니다. 다시 연결해 주세요.");
            stage = "인증 승인 확인";
            JsonNode result = http.send(provider, "POST", endpoint, BrowserPluginHttp.form(Map.of("grant_type", "authorization_code", "client_id", client.id(),
                    "code", code, "redirect_uri", redirect, "code_verifier", attempt.verifier, "resource", provider.resource())), true, null, null, null).body();
            validateScopes(provider, result);
            Grant grant = grant(result, endpoint.toString(), client.id(), null);
            stage = "자료 서비스 연결";
            var session = new PluginMcpSession(provider, http, grant.access());
            session.verifyConnection();
            stage = "연결 저장";
            synchronized (grantLocks.get(provider)) {
                synchronized (this) {
                    if (!active(provider, attempt)) { respond(exchange, 400, "연결이 취소되거나 만료되었습니다."); return; }
                    vault.put(key(provider), json.writeValueAsString(grant));
                    if (provider == BrowserProvider.NOTION) vault.put("notion", null);
                    respond(exchange, 200, "연결되었습니다. Dougle로 돌아가 주세요.");
                    finish(provider, attempt, "CONNECTED", "연결되었습니다.");
                }
            }
        } catch (Exception exception) {
            String message = stage + ": " + (exception instanceof LibraryException ? exception.getMessage() : "연결을 완료하지 못했습니다. 다시 시도해 주세요.");
            try { respond(exchange, 400, accepted ? message : "유효하지 않은 연결 요청입니다."); } catch (Exception ignored) { }
            if (accepted) finish(provider, attempt, "FAILED", message);
        } finally { exchange.close(); }
    }
    static Map<String, String> query(String value) {
        if (value == null || value.length() > 16384) throw new LibraryException(400, "잘못된 인증 응답입니다.");
        var parameters = new HashMap<String, String>();
        for (String part : value.split("&")) {
            String[] pair = part.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            if (parameters.put(name, pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "") != null)
                throw new LibraryException(400, "중복된 인증 응답입니다.");
        }
        return parameters;
    }
    private void respond(HttpExchange exchange, int status, String message) throws java.io.IOException {
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
        byte[] body = ("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><title>Dougle 연결</title><h1>Dougle</h1><p>" + escaped + "</p></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body); exchange.getResponseBody().close();
    }
    public String accessToken(BrowserProvider provider) {
        synchronized (grantLocks.get(provider)) {
            String saved = vault.get(key(provider));
            if (saved == null) throw new LibraryException(409, provider.label + " 연결이 필요합니다. 플러그인에서 연결해 주세요.");
            Grant previous = json.readValue(saved, Grant.class);
            if (previous.expires() > Instant.now().getEpochSecond() + 60) return previous.access();
            if (previous.refresh() == null) { vault.put(key(provider), null); throw new LibraryException(409, "연결이 필요합니다. 인증이 만료되어 다시 연결해야 합니다."); }
            try {
                JsonNode result = http.send(provider, "POST", provider.trusted(previous.tokenEndpoint()), BrowserPluginHttp.form(Map.of("grant_type", "refresh_token",
                        "refresh_token", previous.refresh(), "client_id", previous.clientId(), "resource", provider.resource())), true, null, null, null).body();
                validateScopes(provider, result);
                Grant renewed = grant(result, previous.tokenEndpoint(), previous.clientId(), previous.refresh());
                vault.put(key(provider), json.writeValueAsString(renewed));
                return renewed.access();
            } catch (LibraryException exception) {
                if (exception.status() == 401) vault.put(key(provider), null);
                throw exception;
            }
        }
    }
    public void disconnect(BrowserProvider provider) {
        synchronized (grantLocks.get(provider)) {
            synchronized (this) {
                var attempt = attempts.get(provider);
                if (attempt != null) finish(provider, attempt, "CANCELLED", "연결을 취소했습니다.");
                vault.put(key(provider), null);
            }
        }
    }
    void invalidate(BrowserProvider provider, String token) {
        synchronized (grantLocks.get(provider)) {
            String saved = vault.get(key(provider));
            if (saved != null && json.readValue(saved, Grant.class).access().equals(token)) vault.put(key(provider), null);
        }
    }
    private Grant grant(JsonNode result, String endpoint, String clientId, String previousRefresh) {
        if (!"bearer".equalsIgnoreCase(result.path("token_type").asText())) throw new LibraryException(502, "인증 응답을 확인하지 못했습니다.");
        long lifetime = result.path("expires_in").asLong(3600);
        if (lifetime <= 0 || lifetime > 31536000) throw new LibraryException(502, "인증 유효 기간을 확인하지 못했습니다.");
        String refresh = credential(result, "refresh_token", false);
        return new Grant(credential(result, "access_token", true), refresh == null ? previousRefresh : refresh, Instant.now().getEpochSecond() + lifetime, endpoint, clientId);
    }
    private static void validateScopes(BrowserProvider provider, JsonNode result) {
        if (provider != BrowserProvider.ATLASSIAN || !result.has("scope")) return;
        Set<String> scopes = new HashSet<>(Arrays.asList(result.path("scope").asText("").split("\\s+")));
        if (Collections.disjoint(scopes, Set.of("read:jira:agent-interface", "read:confluence:agent-interface")))
            throw new LibraryException(401, "Jira 또는 Confluence 읽기 승인이 필요합니다. 사용할 서비스를 선택해 다시 연결해 주세요.");
    }
    private static String credential(JsonNode result, String field, boolean required) {
        String value = result.path(field).asText("");
        if (!required && value.isEmpty()) return null;
        if (!value.matches("[\\x21-\\x7e]{1,8192}")) throw new LibraryException(502, "안전한 인증 응답을 확인하지 못했습니다.");
        return value;
    }
    static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String challenge(String verifier) throws NoSuchAlgorithmException { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
    @PreDestroy public synchronized void close() {
        for (var entry : attempts.entrySet()) finish(entry.getKey(), entry.getValue(), "CANCELLED", "앱이 종료되었습니다.");
        timers.shutdownNow(); workers.shutdownNow();
    }
}
