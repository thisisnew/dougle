package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "forge.acceptance.webBoundary", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebBoundaryAcceptanceTest {
    private final List<Map<String, Object>> results = new ArrayList<>();

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/web-boundary-acceptance-results.json"),
                new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 10; fixture++) {
            if (fixture == 8) continue;
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(451 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    private void execute(String id, int fixture, int action) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_HTTP_READER_URL_COLLECTOR_WITH_MOCKED_TRANSPORT_AND_REAL_DEADLINE");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("UI guidance, hover and SourceService persisted error state", "Actual TLS handshake/socket response termination and DNS rebinding", "Semantic search, simultaneous clicks and process restart"));
        long start = System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean healthy = new AtomicBoolean(false);
        var destinations = new ArrayList<String>();
        URI original = URI.create("https://example.org/start");
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpGet request = invocation.getArgument(0);
            calls.incrementAndGet();
            destinations.add(request.getUri().toString());
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getUri().getRawUserInfo()).isNull();
            assertThat(request.getUri().getHost()).isIn("example.org", "example.net");
            if (!healthy.get() && fixture == 3) throw new SSLHandshakeException("synthetic certificate mismatch");
            if (!healthy.get() && fixture == 4) {
                long deadlineStart = System.nanoTime();
                while (!request.isCancelled() && System.nanoTime() - deadlineStart < 30_000_000_000L) Thread.sleep(20);
                result.put("deadlineMillis", (System.nanoTime() - deadlineStart) / 1_000_000);
                assertThat(request.isCancelled()).as("Production 25-second cancellation must fire").isTrue();
                throw new java.io.InterruptedIOException("synthetic transport observed cancellation");
            }
            var response = new BasicClassicHttpResponse(200);
            if (!healthy.get() && fixture < 3) {
                response.setCode(fixture == 0 ? 404 : fixture == 1 ? (action % 2 == 0 ? 401 : 403) : 429);
                if (fixture == 2) response.addHeader("Retry-After", "60");
            } else if (!healthy.get() && fixture >= 5) {
                if (fixture == 5 && request.getUri().getHost().equals("example.net")) {
                    assertThat(request.getFirstHeader("If-None-Match")).isNull();
                    response.setEntity(new StringEntity("BLUE72", org.apache.hc.core5.http.ContentType.TEXT_PLAIN));
                } else {
                    response.setCode(302);
                    response.addHeader("Location", switch (fixture) {
                        case 5 -> "https://example.net/final.txt";
                        case 6 -> request.getUri().getPath().equals("/start") ? "/loop" : "/start";
                        case 7 -> "https://127.0.0.1/private";
                        case 9 -> "https://synthetic-user:synthetic-password@example.net/private";
                        default -> throw new IllegalArgumentException();
                    });
                }
            } else {
                response.setEntity(new StringEntity("BLUE72", org.apache.hc.core5.http.ContentType.TEXT_PLAIN));
                response.addHeader("ETag", "synthetic-v1");
            }
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            var collector = new UrlCollector(reader, null, null, null);
            CollectedSnapshot previous = null;
            try {
                if (action == 1) {
                    healthy.set(true);
                    previous = collector.collect(original.toString());
                    healthy.set(false);
                }
                int before = calls.get();
                if (fixture == 5) {
                    var response = reader.read(original);
                    assertThat(response.uri()).isEqualTo(URI.create("https://example.net/final.txt"));
                    assertThat(new String(response.content(), StandardCharsets.UTF_8)).isEqualTo("BLUE72");
                    assertThat(calls.get() - before).isEqualTo(2);
                    result.put("finalUri", response.uri().toString());
                } else {
                    CollectedSnapshot saved = previous;
                    int attempts = action == 2 && fixture != 4 ? 5 : 1;
                    for (int attempt = 0; attempt < attempts; attempt++) {
                        Throwable failure = catchThrowable(() -> collector.collect(original.toString(), saved));
                        assertThat(failure).isNotNull();
                        assertThat(failure.getMessage()).doesNotContain("synthetic-password");
                        if (fixture < 3 || fixture >= 6) {
                            assertThat(failure).isInstanceOf(LibraryException.class);
                            assertThat(((LibraryException) failure).status()).isEqualTo(fixture == 7 || fixture == 9 ? 400 : 502);
                        } else if (fixture == 3) assertThat(failure).isInstanceOf(SSLHandshakeException.class);
                        else assertThat(failure).isInstanceOf(java.io.InterruptedIOException.class);
                        result.put("observedFailure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    }
                    assertThat(calls.get() - before).isEqualTo(attempts * (fixture == 6 ? 4 : 1));
                    if (fixture == 4 && action == 2) result.put("retryLimit", "One actual deadline tested; five sequential deadlines not repeated");
                }
                if (previous != null) {
                    try (var input = previous.files().getFirst().open()) {
                        assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("BLUE72");
                    }
                }
                if (action == 3) {
                    healthy.set(true);
                    try (var recovered = collector.collect(original.toString())) {
                        assertThat(recovered.files()).hasSize(1);
                    }
                }
                if (action == 4) result.put("hoverScope", "Failure object checked; hover not exercised");
                result.put("status", "PARTIAL");
                result.put("checkStatus", "PASS");
            } finally { if (previous != null) previous.close(); }
        } catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("checkStatus", "FAIL");
            result.put("failure", failure.toString());
            throw failure;
        } finally {
            result.put("transportCalls", calls.get());
            result.put("requestedDestinations", destinations);
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("WEB_BOUNDARY_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }
}
