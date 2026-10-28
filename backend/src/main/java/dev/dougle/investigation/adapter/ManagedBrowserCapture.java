package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.BrowserCaptureScope;
import dev.dougle.investigation.application.BrowserCaptureTransport;
import dev.dougle.investigation.domain.ToolExecution;
import dev.dougle.library.application.LibraryException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ManagedBrowserCapture implements BrowserCaptureTransport {
    private static final String CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "connect-src 'self'; img-src data:; frame-src 'none'; worker-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'";
    private final String configured;

    public ManagedBrowserCapture(@Value("${forge.browser.executable:}") String configured) { this.configured = configured; }

    public Capability capability() {
        return executable() == null
                ? new Capability("SETUP_REQUIRED", "Chrome 또는 Chromium을 직접 설치해 주세요. Mac에서는 /Applications 또는 ~/Applications에 넣고 앱을 다시 여세요. 실행 경로를 별도 지정했다면 그 경로도 확인해 주세요. 브라우저는 동봉되지 않으며 기존 로그인은 가져오지 않습니다.")
                : new Capability("AVAILABLE", "별도 임시 브라우저를 사용할 수 있습니다. 로그인 없는 공개 화면만 지원하며 사이트 호환성은 실행 시 확인합니다.");
    }

    private Path executable() {
        return resolveExecutable(configured, System.getProperty("user.home", ""),
                path -> Files.isRegularFile(path) && Files.isExecutable(path));
    }

    static Path resolveExecutable(String configured, String userHome, java.util.function.Predicate<Path> available) {
        return executableCandidates(configured, userHome).stream().filter(available).findFirst().orElse(null);
    }

    static List<Path> executableCandidates(String configured, String userHome) {
        if (!configured.isBlank()) {
            Path explicit = absolutePath(configured);
            return explicit == null ? List.of() : List.of(explicit);
        }
        var candidates = new ArrayList<Path>();
        for (String application : List.of("Google Chrome.app/Contents/MacOS/Google Chrome", "Chromium.app/Contents/MacOS/Chromium")) {
            candidates.add(Path.of("/Applications").resolve(application));
        }
        Path home = absolutePath(userHome);
        if (home != null) {
            for (String application : List.of("Google Chrome.app/Contents/MacOS/Google Chrome", "Chromium.app/Contents/MacOS/Chromium")) {
                candidates.add(home.resolve("Applications").resolve(application));
            }
        }
        candidates.add(Path.of("/usr/bin/chromium"));
        candidates.add(Path.of("/usr/bin/google-chrome"));
        return List.copyOf(candidates);
    }

    private static Path absolutePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path : null;
        } catch (java.nio.file.InvalidPathException invalid) {
            return null;
        }
    }

    static List<String> command(Path executable, Path profile, int proxyPort) {
        return List.of(executable.toString(), "--headless=new", "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
                "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check", "--disable-extensions",
                "--disable-sync", "--disable-background-networking", "--disable-component-update", "--disable-default-apps",
                "--disable-breakpad", "--disable-crash-reporter", "--disable-quic", "--disable-domain-reliability",
                "--password-store=basic", "--use-mock-keychain",
                "--proxy-server=http://127.0.0.1:" + proxyPort, "--proxy-bypass-list=<-loopback>",
                "--host-resolver-rules=MAP * ~NOTFOUND", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
                "--window-size=1280,900", "about:blank");
    }

    public Snapshot capture(BrowserCaptureScope scope, ToolExecution.Context context) throws Exception {
        Path executable = executable();
        if (executable == null) throw new LibraryException(503, capability().message());
        context.checkpoint();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_SECONDS);
        Path profile = Files.createTempDirectory("dougle-browser-capture-");
        Process process = null;
        Set<ProcessHandle> descendants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (ServerSocket deniedProxy = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
             BrowserHttpGateway gateway = gateway()) {
            Thread.ofVirtual().start(() -> {
                while (!deniedProxy.isClosed()) {
                    try (var ignored = deniedProxy.accept()) { }
                    catch (IOException closed) { break; }
                }
            });
            ProcessBuilder builder = new ProcessBuilder(command(executable, profile, deniedProxy.getLocalPort()));
            builder.environment().keySet().retainAll(Set.of("PATH", "LANG", "LC_ALL", "SYSTEMROOT", "SystemRoot", "WINDIR"));
            builder.environment().put("HOME", profile.toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            Path endpointFile = profile.resolve("DevToolsActivePort");
            while (!Files.isRegularFile(endpointFile)) {
                checkpoint(context, deadline);
                process.descendants().forEach(descendants::add);
                if (!process.isAlive()) throw new IOException("Browser could not start");
                Thread.sleep(25);
            }
            if (Files.size(endpointFile) > 2048) throw new IOException("Invalid browser endpoint");
            var address = Files.readAllLines(endpointFile);
            if (address.size() < 2 || !address.get(0).matches("[0-9]{1,5}") || !address.get(1).matches("/devtools/browser/[a-zA-Z0-9-]+")) {
                throw new IOException("Invalid browser endpoint");
            }
            try (var cdp = new BrowserCdpConnection(URI.create("ws://127.0.0.1:" + address.get(0) + address.get(1)))) {
                return render(cdp, gateway, scope, context, deadline);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (process != null) {
                process.descendants().forEach(descendants::add);
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException cancelled) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            try (var paths = Files.walk(profile)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { }
        }
    }

    BrowserHttpGateway gateway() { return new BrowserHttpGateway(); }

    private Snapshot render(BrowserCdpConnection cdp, BrowserHttpGateway gateway, BrowserCaptureScope scope,
                            ToolExecution.Context context, long deadline) throws Exception {
        String target = cdp.command("Target.createTarget", Map.of("url", "about:blank"), null, deadline).path("targetId").asString();
        String session = cdp.command("Target.attachToTarget", Map.of("targetId", target, "flatten", true), null, deadline).path("sessionId").asString();
        cdp.command("Browser.setDownloadBehavior", Map.of("behavior", "deny"), null, deadline);
        cdp.command("Page.enable", Map.of(), session, deadline);
        cdp.command("Network.enable", Map.of(), session, deadline);
        cdp.command("Network.setBypassServiceWorker", Map.of("bypass", true), session, deadline);
        cdp.command("Network.setCacheDisabled", Map.of("cacheDisabled", true), session, deadline);
        cdp.command("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))), session, deadline);
        String frame = cdp.command("Page.getFrameTree", Map.of(), session, deadline).path("frameTree").path("frame").path("id").asString();
        var navigationCommand = cdp.dispatch("Page.navigate", Map.of("url", scope.url()), session);
        long renderUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
        var observations = new ArrayList<Observation>();
        boolean navigation = false;
        boolean authRequired = false;
        boolean navigationBlocked = false;
        boolean truncated = false;
        long lastRequest = System.nanoTime();
        while (System.nanoTime() < renderUntil) {
            checkpoint(context, deadline);
            JsonNode event = cdp.event();
            if (event == null) {
                if (navigation && System.nanoTime() - lastRequest > TimeUnit.SECONDS.toNanos(2)) break;
                continue;
            }
            if (!"Fetch.requestPaused".equals(event.path("method").asString()) || !session.equals(event.path("sessionId").asString())) continue;
            var paused = event.path("params");
            var request = paused.path("request");
            String id = paused.path("requestId").asString();
            String url = request.path("url").asString();
            String method = request.path("method").asString();
            String type = paused.path("resourceType").asString();
            boolean document = "Document".equals(type);
            boolean allowed = observations.size() < MAX_REQUESTS && scope.allows(method, url, document)
                    && (!document || (!navigation && frame.equals(paused.path("frameId").asString())))
                    && Set.of("Document", "Script", "Stylesheet", "XHR", "Fetch").contains(type);
            lastRequest = System.nanoTime();
            if (!allowed) {
                cdp.command("Fetch.failRequest", Map.of("requestId", id, "errorReason", "BlockedByClient"), session, deadline);
                if (observations.size() < MAX_REQUESTS) observations.add(new Observation(safeMethod(method), scope.observationPath(url), null, true,
                        Set.of("Script", "Stylesheet", "XHR", "Fetch").contains(type) ? scope.candidatePath(method, url) : null));
                else { truncated = true; break; }
                continue;
            }
            try {
                var response = gateway.read(scope, url, context, deadline);
                boolean blocked = response.status() < 200 || response.status() >= 300;
                observations.add(new Observation(method, scope.observationPath(url), response.status(), blocked));
                if (document) {
                    navigation = true;
                    authRequired = response.status() == 401 || response.status() == 403;
                    navigationBlocked = blocked;
                }
                var headers = List.of(Map.of("name", "Content-Type", "value", response.contentType()),
                        Map.of("name", "Content-Security-Policy", "value", CSP), Map.of("name", "Cache-Control", "value", "no-store"),
                        Map.of("name", "Referrer-Policy", "value", "no-referrer"), Map.of("name", "X-Content-Type-Options", "value", "nosniff"));
                cdp.command("Fetch.fulfillRequest", Map.of("requestId", id, "responseCode", response.status(),
                        "responseHeaders", headers, "body", Base64.getEncoder().encodeToString(response.body())), session, deadline);
            } catch (IOException failure) {
                observations.add(new Observation(method, scope.observationPath(url), null, true));
                cdp.command("Fetch.failRequest", Map.of("requestId", id, "errorReason", "BlockedByClient"), session, deadline);
            }
        }
        checkpoint(context, deadline);
        truncated |= System.nanoTime() >= renderUntil;
        if (!navigation) throw new IOException("Approved navigation was not captured");
        var navigationResult = navigationCommand.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (navigationResult.has("error") || navigationResult.path("result").has("errorText")) throw new IOException("Browser navigation failed");
        cdp.command("Page.stopLoading", Map.of(), session, deadline);
        int world = cdp.command("Page.createIsolatedWorld", Map.of("frameId", frame, "worldName", "dougle-capture"), session, deadline)
                .path("executionContextId").asInt();
        var result = cdp.command("Runtime.evaluate", Map.of("contextId", world, "returnByValue", true, "timeout", 1000,
                "expression", "JSON.stringify({title:document.title.slice(0,256),text:(document.body?.innerText||'').slice(0,6000),truncated:(document.body?.innerText||'').length>6000})"),
                session, deadline);
        if (result.has("exceptionDetails") || !result.path("result").path("value").isString()) throw new IOException("Rendered capture unavailable");
        var content = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.path("result").path("value").asString());
        return new Snapshot(scope.url(), Instant.now().toString(), content.path("title").asString(), content.path("text").asString(),
                observations, truncated || content.path("truncated").asBoolean(),
                authRequired ? "AUTH_REQUIRED" : navigationBlocked ? "NAVIGATION_BLOCKED" : "CAPTURED");
    }

    private static String safeMethod(String method) {
        return Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "CONNECT", "TRACE").contains(method) ? method : "[redacted]";
    }

    private static void checkpoint(ToolExecution.Context context, long deadline) throws IOException {
        context.checkpoint();
        if (System.nanoTime() >= deadline) throw new IOException("Browser capture deadline");
    }
}
