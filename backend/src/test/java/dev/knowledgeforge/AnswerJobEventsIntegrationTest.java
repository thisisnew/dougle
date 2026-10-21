package dev.knowledgeforge;

import dev.knowledgeforge.conversation.application.AnswerJobService;
import dev.knowledgeforge.conversation.application.AnswerModel;
import dev.knowledgeforge.conversation.domain.AnswerJob;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(15)
class AnswerJobEventsIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-answer-events-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @LocalServerPort int port;
    @MockitoBean AnswerJobService jobs;
    @MockitoBean EvidenceBundleService bundles;
    @MockitoBean AnswerModel model;
    AtomicReference<AnswerJob> current;
    List<HttpURLConnection> connections = new ArrayList<>();

    @BeforeEach
    void prepare() {
        current = new AtomicReference<>(job("RUNNING", null));
        when(jobs.get(anyString(), anyString())).thenAnswer(invocation -> {
            if (!"workspace".equals(invocation.getArgument(0)) || !"job".equals(invocation.getArgument(1))) {
                throw new LibraryException(404, "답변 작업을 찾을 수 없습니다.");
            }
            var result = current.get();
            if (result == null) throw new LibraryException(404, "삭제됨");
            return result;
        });
        when(bundles.get("workspace", "bundle")).thenReturn(new EvidenceBundle("bundle", "workspace", "now", "digest",
                new EvidenceBundle.Payload("가상 질문", List.of(), false, "READY", 0, 12000, List.of(), List.of())));
    }

    @AfterEach
    void cleanup() throws Exception {
        current.set(null);
        connections.forEach(HttpURLConnection::disconnect);
        Thread.sleep(600);
        verify(jobs, never()).submit(anyString(), anyString(), any());
        verify(jobs, never()).cancel(anyString(), anyString());
        verifyNoInteractions(model);
    }

    @Test
    void evidencePrecedesCompletedAnswerAndReconnectOnlyReplaysSnapshot() throws Exception {
        current.set(job("SUCCEEDED", "가상 답변 [R1]\n두 번째 줄"));
        for (int attempt = 0; attempt < 2; attempt++) {
            var connection = connect("workspace", "job");
            connection.setRequestProperty("Last-Event-ID", "ignored-old-id");
            assertThat(connection.getResponseCode()).isEqualTo(200);
            assertThat(connection.getContentType()).startsWith("text/event-stream");
            assertThat(connection.getHeaderField("Cache-Control")).isEqualTo("no-store");
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("가상 질문", "가상 답변 [R1]", "event:done", "terminal");
            assertThat(body.indexOf("event:evidence")).isLessThan(body.indexOf("event:job"));
            assertThat(body.indexOf("event:job")).isLessThan(body.indexOf("event:done"));
            assertThat(body).doesNotContain("event:delta");
        }
    }

    @Test
    void observesStateChangesWithoutStartingOrCancellingJob() throws Exception {
        var connection = connect("workspace", "job");
        try (var reader = reader(connection)) {
            assertThat(event(reader)).contains("event:evidence");
            assertThat(event(reader)).contains("event:job", "RUNNING");
            current.set(job("CANCELLED", null));
            assertThat(event(reader)).contains("event:job", "CANCELLED");
            assertThat(event(reader)).contains("event:done", "terminal");
        }
    }

    @Test
    void deletedJobEndsObservationWithoutLeakingExceptionDetails() throws Exception {
        try (var reader = reader(connect("workspace", "job"))) {
            event(reader); event(reader);
            current.set(null);
            assertThat(event(reader)).contains("event:unavailable").doesNotContain("삭제됨");
            assertThat(reader.readLine()).isNull();
        }
    }

    @Test
    void wrongWorkspaceAndMissingJobReturn404BeforeStreaming() throws Exception {
        assertThat(connect("other", "job").getResponseCode()).isEqualTo(404);
        assertThat(connect("workspace", "missing").getResponseCode()).isEqualTo(404);
    }

    @Test
    void untrustedOriginIsRejected() throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/workspaces/workspace/answer-jobs/job/events"))
                    .header("Origin", "https://untrusted.example").build();
            assertThat(client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        verifyNoInteractions(jobs, bundles);
    }

    @Test
    void limitsConcurrentObserversAndReleasesCapacityAfterCompletion() throws Exception {
        for (int count = 0; count < 16; count++) {
            var reader = reader(connect("workspace", "job"));
            assertThat(event(reader)).contains("event:evidence");
            assertThat(event(reader)).contains("RUNNING");
        }
        assertThat(connect("workspace", "job").getResponseCode()).isEqualTo(429);
        current.set(job("FAILED", null));
        Thread.sleep(700);
        var connection = connect("workspace", "job");
        assertThat(connection.getResponseCode()).isEqualTo(200);
        assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("FAILED", "event:done");
    }

    private HttpURLConnection connect(String workspace, String id) throws Exception {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/api/workspaces/" + workspace + "/answer-jobs/" + id + "/events").toURL().openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
        connections.add(connection);
        return connection;
    }

    private BufferedReader reader(HttpURLConnection connection) throws Exception {
        assertThat(connection.getResponseCode()).isEqualTo(200);
        return new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
    }

    private String event(BufferedReader reader) throws Exception {
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) result.append(line).append('\n');
        return result.toString();
    }

    private AnswerJob job(String status, String answer) {
        return new AnswerJob("job", "workspace", "bundle", "approval", "CODEX", "", "digest", status, answer, null, "now", "now");
    }
}
