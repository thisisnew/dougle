package dev.knowledgeforge.plugins.adapter;

import com.sun.net.httpserver.HttpServer;
import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.plugins.slack.application.*;
import dev.knowledgeforge.plugins.slack.domain.SlackEvent;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "forge.acceptance.operational", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalAcceptanceTest {
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final JsonMapper json = new JsonMapper();
    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/operational-acceptance-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 5; fixture++) for (int action = 0; action < 5; action++) {
            int selectedFixture = fixture;
            int selectedAction = action;
            String id = "T%04d".formatted(901 + fixture * 5 + action);
            tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
        }
        return tests.stream();
    }
    private void execute(String id, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_SLACK_INBOX_WITH_MEMORY_STORE_MOCK_PROVIDER_OR_REAL_HTTP_DATADOG_ADAPTER_LOOPBACK_SERVER");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("AI decides whether/how to investigate; actual company services and thread summarization", "UI approval and durable SQLite restart", "10,000-event stress and code/log version explanation not performed"));
        long started = System.nanoTime();
        try {
            if (fixture == 0 || fixture == 1 || fixture == 3) slack(fixture, action, result);
            else logs(fixture, action, result);
            result.put("status", "PARTIAL"); result.put("checkStatus", "PASS");
        } catch (Throwable failure) {
            result.put("status", "FAIL"); result.put("checkStatus", "FAIL"); result.put("failure", failure.toString()); throw failure;
        } finally { result.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); results.add(result); }
    }
    private void slack(int fixture, int action, Map<String, Object> result) {
        var provider = mock(SlackProvider.class);
        var library = mock(LibraryStore.class);
        var threads = mock(ChatThreadService.class);
        var scheduler = mock(SlackReconnectScheduler.class);
        var listener = new AtomicReference<SlackProvider.Listener>();
        var store = new MemorySlackInboxStore();
        when(library.workspace("one")).thenReturn(Optional.of(new Workspace("one", "Synthetic", "now")));
        when(provider.authenticate("xoxb-synthetic")).thenReturn(new SlackProvider.Identity("TTEAM", "UBOT"));
        when(provider.open(eq("xapp-synthetic"), any())).thenAnswer(invocation -> { listener.set(invocation.getArgument(1)); return mock(SlackProvider.Session.class); });
        var service = new SlackInboxService(provider, library, threads, store, scheduler);
        try {
            if (fixture == 0) {
                assertThat(service.inbox("one")).isEmpty();
                assertThat(service.status().state()).isEqualTo("NOT_CONFIGURED");
                verifyNoInteractions(provider, threads);
            } else {
                var credentials = new SlackInboxService.Credentials("xapp-synthetic", "xoxb-synthetic", true);
                service.connect(credentials); listener.get().ready(); service.route("CCHANNEL", "one");
                var event = new SlackEvent("EvSYNTHETIC", "TTEAM", "CCHANNEL", "UHUMAN", "1800000000.000001", "Synthetic TEST-42 inquiry");
                listener.get().event(event);
                if (fixture == 3 || action == 3) {
                    for (int repeat = 0; repeat < 10; repeat++) listener.get().event(event);
                    service.disconnect(); service.connect(credentials); listener.get().ready(); listener.get().event(event);
                }
                listener.get().event(new SlackEvent("EvOTHER", "TTEAM", "COTHER", "UHUMAN", "1800000000.000002", "OTHER_CHANNEL_SECRET"));
                assertThat(service.inbox("one")).hasSize(1);
                assertThat(service.inbox("one").getFirst().event().text()).contains("TEST-42").doesNotContain("OTHER_CHANNEL_SECRET");
                verifyNoInteractions(threads);
                result.put("inboxCount", service.inbox("one").size());
            }
            result.put("providerCalls", mockingDetails(provider).getInvocations().size());
        } finally { service.disconnect(); }
    }
    private void logs(int fixture, int action, Map<String, Object> result) throws Exception {
        Instant from = Instant.parse("2026-10-27T10:00:00Z");
        Instant to = from.plusSeconds(600);
        var calls = new AtomicInteger();
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var response = Map.of("data", List.of(
                Map.of("id", "one", "attributes", Map.of("timestamp", "2026-10-27T10:05:00Z", "service", "orders", "tags", List.of("env:test", "version:old"), "message", "TEST-42 failure password=synthetic-secret")),
                Map.of("id", "two", "attributes", Map.of("timestamp", "2026-10-27T10:06:00Z", "service", "orders", "tags", List.of("env:test"), "message", "TEST-43 other customer"))));
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = json.writeValueAsBytes(response);
            try { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = new DatadogLogsClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/logs/events/search"))) {
            var config = new DatadogLogsClient.Config(DatadogLogsClient.Site.US1, "synthetic-api-key", "synthetic-app-key");
            var policy = new DatadogLogsClient.Policy(Map.of("test", Set.of("orders")), Set.of("synthetic"), from, to, Duration.ofMinutes(10), 10, 4096, Duration.ofSeconds(3));
            if (action == 1) {
                assertThatThrownBy(() -> client.read(config, policy, new DatadogLogsClient.Query("production", "orders", from, to, 2), () -> {})).isInstanceOf(IllegalArgumentException.class);
                assertThat(calls.get()).isZero();
            }
            var output = client.read(config, policy, new DatadogLogsClient.Query("test", "orders", from, to, 2), () -> {});
            result.put("request", request.get()); result.put("output", output.output()); result.put("transportCalls", calls.get());
            assertThat(request.get()).startsWith("POST /api/v2/logs/events/search ").contains("10:00:00Z", "10:10:00Z", "orders");
            assertThat(output.output()).doesNotContain("synthetic-secret", "synthetic-api-key", "synthetic-app-key");
            if (fixture == 2) assertThat(output.output()).as("TEST-42 scoped inquiry must not return TEST-43; typed Query currently lacks request ID filtering").doesNotContain("TEST-43");
            else result.put("versionScope", "Code version comparison/uncertainty requires answer layer; not verified here");
        } finally { server.stop(0); }
    }
}
