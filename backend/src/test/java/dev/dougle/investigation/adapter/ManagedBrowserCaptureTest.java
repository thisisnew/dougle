package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.BrowserCaptureScope;
import dev.dougle.investigation.application.BrowserCaptureTransport;
import dev.dougle.investigation.domain.ToolExecution;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedBrowserCaptureTest {
    @Test void discoversSystemThenActualUserApplicationsWithoutChangingGlobalHome() {
        String home = "/synthetic/user home";
        var candidates = ManagedBrowserCapture.executableCandidates("", home);
        assertThat(candidates).containsExactly(
                Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"),
                Path.of(home, "Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                Path.of(home, "Applications/Chromium.app/Contents/MacOS/Chromium"),
                Path.of("/usr/bin/chromium"), Path.of("/usr/bin/google-chrome"));
        for (Path candidate : candidates.subList(0, 4)) {
            assertThat(ManagedBrowserCapture.resolveExecutable("", home, candidate::equals)).isEqualTo(candidate);
        }
        assertThat(ManagedBrowserCapture.resolveExecutable("", home, path -> true)).isEqualTo(candidates.getFirst());
        assertThat(ManagedBrowserCapture.resolveExecutable("", home, path -> false)).isNull();
    }

    @Test void configuredExecutableIsExclusiveEvenWhenMissingRelativeOrMalformed() {
        var custom = Path.of("/synthetic/custom chrome");
        assertThat(ManagedBrowserCapture.executableCandidates(custom.toString(), "/synthetic/home")).containsExactly(custom);
        assertThat(ManagedBrowserCapture.resolveExecutable(custom.toString(), "/synthetic/home", custom::equals)).isEqualTo(custom);
        assertThat(ManagedBrowserCapture.resolveExecutable(custom.toString(), "/synthetic/home", path -> !path.equals(custom))).isNull();
        for (String invalid : List.of("relative/chrome", "~/Applications/Chrome", "/invalid\u0000chrome")) {
            assertThat(ManagedBrowserCapture.resolveExecutable(invalid, "/synthetic/home", path -> true)).isNull();
        }
    }

    @Test void invalidHomeNeverIntroducesRelativeDiscoveryPaths() {
        for (String home : List.of("", "relative/home", "/invalid\u0000home")) {
            assertThat(ManagedBrowserCapture.executableCandidates("", home))
                    .hasSize(4).allMatch(Path::isAbsolute);
        }
    }

    @Test void commandUsesDisposableProfileAndDenyProxyWithoutSandboxBypass() {
        var command = ManagedBrowserCapture.command(Path.of("/fake/chrome"), Path.of("/tmp/fresh-browser"), 12345);
        assertThat(command).contains("--user-data-dir=/tmp/fresh-browser", "--proxy-server=http://127.0.0.1:12345",
                "--proxy-bypass-list=<-loopback>", "--host-resolver-rules=MAP * ~NOTFOUND", "--disable-extensions",
                "--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--headless=new")
                .doesNotContain("--no-sandbox", "--disable-web-security", "--no-proxy-server", "--ignore-certificate-errors");
    }

    @Test void missingExecutableReportsSetupWithoutLaunch() {
        var capture = new ManagedBrowserCapture("/does-not-exist/dougle-chromium");
        assertThat(capture.capability().status()).isEqualTo("SETUP_REQUIRED");
        assertThat(capture.capability().message()).contains("/Applications", "~/Applications", "직접 설치", "기존 로그인은 가져오지 않습니다");
        assertThatThrownBy(() -> capture.capture(new BrowserCaptureScope("https://example.com/", List.of("/")),
                new ToolExecution.Context(new ToolExecution.Binding("workspace", "isolated-browser", "https://example.com"), () -> false)))
                .isInstanceOf(dev.dougle.library.application.LibraryException.class);
    }

    @Test void toolCannotUseAnotherScopeOrBinding() throws Exception {
        var transport = mock(BrowserCaptureTransport.class);
        var scope = new BrowserCaptureScope("https://example.com/", List.of("/"));
        var binding = new ToolExecution.Binding("workspace", "isolated-browser", scope.origin());
        var tool = new BrowserCaptureTool(transport, binding, scope);
        assertThatThrownBy(() -> tool.execute(new BrowserCaptureScope("https://other.example/", List.of("/")),
                new ToolExecution.Context(binding, () -> false), value -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(new BrowserCaptureScope(scope.url(), List.of("/", "/api/candidate")),
                new ToolExecution.Context(binding, () -> false), value -> {})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.execute(scope,
                new ToolExecution.Context(new ToolExecution.Binding("other", "isolated-browser", scope.origin()), () -> false),
                value -> {})).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transport);
    }

    @Test
    @EnabledIfSystemProperty(named = "forge.test.isolated-browser", matches = "true")
    void actualChromiumRendersSyntheticApiDataAndBlocksMutationsWithoutRemoteTraffic() throws Exception {
        String executable = System.getProperty("forge.test.browser-executable",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(executable)));
        var reads = new CopyOnWriteArrayList<String>();
        BrowserHttpGateway gateway = mock(BrowserHttpGateway.class);
        when(gateway.read(any(), anyString(), any(), anyLong())).thenAnswer(call -> {
            String url = call.getArgument(1);
            reads.add(url);
            String body;
            String type;
            if (url.endsWith("/api/status")) {
                body = "{\"state\":\"synthetic healthy\"}";
                type = "application/json";
            } else {
                body = """
                        <!doctype html><html><head><title>Loading</title></head><body>Waiting<script>
                        fetch('/api/status').then(response => response.json()).then(data => {
                          document.title = 'Rendered fixture'; document.body.innerText = data.state;
                        });
                        fetch('/api/status', {method:'POST', body:'PRIVATE'}).catch(()=>{});
                        fetch('/api/status?secret=PRIVATE').catch(()=>{});
                        fetch('/api/metrics?secret=PRIVATE').catch(()=>{});
                        fetch('https://outside.example/private-token').catch(()=>{});
                        </script></body></html>
                        """;
                type = "text/html";
            }
            return new BrowserHttpGateway.Response(200, type, body.getBytes(StandardCharsets.UTF_8));
        });
        var capture = new ManagedBrowserCapture(executable) {
            @Override BrowserHttpGateway gateway() { return gateway; }
        };
        var scope = new BrowserCaptureScope("https://example.com/app", List.of("/app", "/api/status"));
        var snapshot = capture.capture(scope, new ToolExecution.Context(
                new ToolExecution.Binding("fixture", "isolated-browser", scope.origin()), () -> false));
        assertThat(snapshot.title()).isEqualTo("Rendered fixture");
        assertThat(snapshot.text()).isEqualTo("synthetic healthy");
        assertThat(reads).containsExactly("https://example.com/app", "https://example.com/api/status");
        assertThat(snapshot.observations()).anyMatch(item -> item.method().equals("POST") && item.blocked());
        assertThat(snapshot.observations()).anyMatch(item -> item.blocked() && "/api/metrics".equals(item.candidatePath()));
        assertThat(snapshot.observations().toString()).doesNotContain("PRIVATE", "private-token", "?secret");
        assertThat(snapshot.status()).isEqualTo("CAPTURED");
        verify(gateway).close();
        String artifact = System.getProperty("forge.test.browser-evidence");
        if (artifact != null) {
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var evidence = json.createObjectNode().put("kind", "isolated-chromium-synthetic-fixture")
                    .put("externalAiCalls", 0).put("remoteHttpRequests", 0).put("userProfileUsed", false);
            evidence.set("snapshot", json.valueToTree(snapshot));
            evidence.set("gatewayRequests", json.valueToTree(reads));
            Files.writeString(Path.of(artifact), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
}
