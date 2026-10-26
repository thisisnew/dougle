package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.adapter.DatadogConnections;
import dev.knowledgeforge.plugins.adapter.DatadogLogsClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class DatadogInvestigationServiceTest {
    private final DatadogConnections connections = mock(DatadogConnections.class);
    private final DatadogLogsClient client = mock(DatadogLogsClient.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");
    private final String revision = "5d5cc45e-f6ef-4689-8450-a021e35e9cf9";
    private final String requestId = "request-synthetic-1234";
    private final DatadogLogsClient.Config config = new DatadogLogsClient.Config(DatadogLogsClient.Site.EU1,
            "synthetic_api_key_0000", "synthetic_app_key_0000");
    private DatadogInvestigationService service;
    private DatadogInvestigationController controller;

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(now);
        when(connections.require("workspace", revision)).thenReturn(config);
        when(client.read(eq(config), any(), any(), any())).thenReturn(new DatadogLogsClient.Result("safe log", 1, true));
        service = new DatadogInvestigationService(connections, client, clock);
        controller = new DatadogInvestigationController(service);
    }

    private DatadogInvestigationService.Query query() {
        return new DatadogInvestigationService.Query(revision, "test", "orders", Set.of("fixture"), now.minusSeconds(600), now, 20);
    }

    @Test
    void provenancePinsUtcScopeMasksCredentialsAndExpiresWithOriginalApproval() {
        var input = new DatadogInvestigationService.Query(revision, "test", config.apiKey(), Set.of(config.applicationKey()), now.minusSeconds(600), now, 20);
        var preview = service.preview("workspace", input);
        var scope = service.provenance("workspace", preview.id());
        assertTrue(scope.text().contains("\"environment\":\"test\""));
        assertTrue(scope.text().contains("\"service\":\"[REDACTED]\""));
        assertTrue(scope.text().contains("\"indexes\":[\"[REDACTED]\"]"));
        assertTrue(scope.text().contains("\"from\":\"2026-10-25T11:50:00Z\""));
        assertTrue(scope.text().contains("\"to\":\"2026-10-25T12:00:00Z\""));
        assertTrue(scope.text().contains("\"timezone\":\"UTC\""));
        assertFalse(scope.text().contains(config.apiKey()));
        assertFalse(scope.text().contains(config.applicationKey()));
        when(clock.instant()).thenReturn(now.plusSeconds(601));
        assertThrows(LibraryException.class, () -> service.provenance("workspace", preview.id()));
    }

    @Test
    void controllerPinsQueryAndReturnsSingleUseReceiptWithoutCredentials() throws Exception {
        var json = new JsonMapper();
        var preview = controller.preview("workspace", request(json.writeValueAsString(query())));
        assertTrue(preview.sessionOnly());
        assertEquals("EU1", preview.site());
        assertEquals(query(), preview.query());
        assertEquals(now.plusSeconds(600).toString(), preview.expiresAt());
        assertFalse(json.writeValueAsString(preview).contains(config.apiKey()));
        verifyNoInteractions(client);
        assertEquals("PREVIEW", controller.result("workspace", preview.id()).status());
        var result = controller.execute("workspace", preview.id(), request("{\"requestId\":\"" + requestId + "\",\"approved\":true}"));
        assertEquals("OK", result.status());
        assertEquals("safe log", result.output());
        assertTrue(result.truncated());
        assertEquals(result, service.execute("workspace", preview.id(), requestId, true));
        assertEquals(result, controller.result("workspace", preview.id()));
        assertEquals(409, assertThrows(LibraryException.class,
                () -> service.execute("workspace", preview.id(), "different-request-123", true)).status());
        verify(client).read(eq(config), argThat(policy -> policy.servicesByEnvironment().equals(java.util.Map.of("test", Set.of("orders")))
                && policy.indexes().equals(Set.of("fixture")) && policy.maxBytes() == 16384 && policy.timeout().toSeconds() == 10),
                argThat(input -> input.environment().equals("test") && input.service().equals("orders")
                        && input.from().equals(query().from()) && input.to().equals(query().to()) && input.limit() == 20), any());
    }

    @Test
    void rejectsUnapprovedCrossWorkspaceUnknownAndChangedConnectionBeforeNetwork() {
        var preview = service.preview("workspace", query());
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), requestId, false));
        assertThrows(LibraryException.class, () -> service.execute("other", preview.id(), requestId, true));
        assertThrows(LibraryException.class, () -> service.execute("workspace", "missing", requestId, true));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), "short", true));
        when(connections.require("workspace", revision)).thenThrow(new LibraryException(409, "changed"));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), requestId, true));
        verifyNoInteractions(client);
    }

    @Test
    void expiryRestartAndDeletedWorkspaceFailClosed() {
        var preview = service.preview("workspace", query());
        var restarted = new DatadogInvestigationService(connections, client, clock);
        assertEquals(404, assertThrows(LibraryException.class, () -> restarted.result("workspace", preview.id())).status());
        when(connections.status("workspace")).thenThrow(new LibraryException(404, "deleted"));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), requestId, true));
        reset(connections);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertEquals(409, assertThrows(LibraryException.class, () -> service.result("workspace", preview.id())).status());
        verifyNoInteractions(client);
    }

    @Test
    void boundedPreviewCapacityAndExpiredCleanup() {
        for (int count = 0; count < 32; count++) service.preview("workspace", query());
        assertEquals(429, assertThrows(LibraryException.class, () -> service.preview("workspace", query())).status());
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertNotNull(service.preview("workspace", query()));
        verifyNoInteractions(client);
    }

    @Test
    void validatesTagsIndexesTimeAndRows() {
        var valid = query();
        for (var invalid : java.util.List.of(
                new DatadogInvestigationService.Query(revision, "test OR *", "orders", valid.indexes(), valid.from(), now, 20),
                new DatadogInvestigationService.Query(revision, "test", "*", valid.indexes(), valid.from(), now, 20),
                new DatadogInvestigationService.Query(revision, "test", "orders", Set.of("*"), valid.from(), now, 20),
                new DatadogInvestigationService.Query(revision, "test", "orders", Set.of(), valid.from(), now, 20),
                new DatadogInvestigationService.Query(revision, "test", "orders", valid.indexes(), valid.from(), now, 51),
                new DatadogInvestigationService.Query(revision, "test", "orders", valid.indexes(), now.minusSeconds(3601), now, 20),
                new DatadogInvestigationService.Query(revision, "test", "orders", valid.indexes(), now, now.plusSeconds(1), 20),
                new DatadogInvestigationService.Query(revision, "test", "orders", valid.indexes(), now.minusSeconds(8 * 86400), now.minusSeconds(8 * 86400 - 1), 20))) {
            assertEquals(400, assertThrows(LibraryException.class, () -> service.preview("workspace", invalid)).status());
        }
        verifyNoInteractions(client);
    }

    @Test
    void strictControllerRejectsArbitraryQueryAndCoercion() {
        var json = new JsonMapper();
        String valid = json.writeValueAsString(query());
        for (String body : java.util.List.of("null", "{}", valid + "{}", valid.replace("\"limit\":20", "\"limit\":\"20\""),
                valid.replace("\"limit\":20", "\"limit\":20,\"limit\":1"), valid.replace("\"limit\":20", "\"limit\":20,\"query\":\"*\""), "x".repeat(8193))) {
            assertThrows(LibraryException.class, () -> controller.preview("workspace", request(body)));
        }
        for (String body : java.util.List.of("{\"requestId\":\"" + requestId + "\"}",
                "{\"requestId\":\"" + requestId + "\",\"approved\":\"true\"}",
                "{\"requestId\":\"" + requestId + "\",\"approved\":true,\"environment\":\"prod\"}")) {
            assertThrows(LibraryException.class, () -> controller.execute("workspace", "missing", request(body)));
        }
        verifyNoInteractions(client);
    }

    @Test
    void cancellationBeforeReadConsumesPreviewWithoutCallingClient() {
        var preview = service.preview("workspace", query());
        controller.cancel("workspace", preview.id());
        assertEquals("CANCELLED", controller.result("workspace", preview.id()).status());
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), requestId, true));
        verifyNoInteractions(client);
    }

    @Test
    void cancellationAndDuplicateWhileRunningAreBounded() throws Exception {
        var preview = service.preview("workspace", query());
        var other = service.preview("workspace", query());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.read(eq(config), any(), any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            invocation.getArgument(3, Runnable.class).run();
            return new DatadogLogsClient.Result("should not escape", 1, false);
        });
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var running = CompletableFuture.supplyAsync(() -> service.execute("workspace", preview.id(), requestId, true), workers);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("RUNNING", service.execute("workspace", preview.id(), requestId, true).status());
                assertThrows(LibraryException.class, () -> service.execute("workspace", other.id(), requestId, true));
                controller.cancel("workspace", preview.id());
            } finally { release.countDown(); }
            var result = running.get(3, TimeUnit.SECONDS);
            assertEquals("CANCELLED", result.status());
            assertEquals("", result.output());
        }
        verify(client).read(eq(config), any(), any(), any());
    }

    @Test
    void connectionRotationAfterReadDiscardsEvidence() {
        var preview = service.preview("workspace", query());
        when(client.read(eq(config), any(), any(), any())).thenAnswer(invocation -> {
            when(connections.require("workspace", revision)).thenThrow(new LibraryException(409, "private detail"));
            return new DatadogLogsClient.Result("private logs", 1, false);
        });
        var result = service.execute("workspace", preview.id(), requestId, true);
        assertEquals("DENIED", result.status());
        assertEquals("", result.output());
        assertThrows(LibraryException.class, () -> service.result("workspace", preview.id()));
    }

    @Test
    void emptyTimeoutDeniedAndFailureAreDistinctAndSanitized() {
        when(client.read(eq(config), any(), any(), any())).thenReturn(new DatadogLogsClient.Result("", 0, false));
        assertEquals("EMPTY", service.execute("workspace", service.preview("workspace", query()).id(), requestId, true).status());
        for (var entry : java.util.Map.of(504, "TIMEOUT", 403, "DENIED", 502, "FAILED").entrySet()) {
            when(client.read(eq(config), any(), any(), any())).thenThrow(new LibraryException(entry.getKey(), "secret"));
            var result = service.execute("workspace", service.preview("workspace", query()).id(), requestId, true);
            assertEquals(entry.getValue(), result.status());
            assertEquals("", result.output());
            reset(client);
        }
    }

    @Test
    void completedReceiptsAndSameRequestReplaysExpireWithoutAnotherPreview() {
        var first = service.preview("workspace", query());
        var second = service.preview("workspace", query());
        service.execute("workspace", first.id(), requestId, true);
        service.execute("workspace", second.id(), requestId, true);
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertEquals(409, assertThrows(LibraryException.class, () -> service.result("workspace", first.id())).status());
        assertEquals(409, assertThrows(LibraryException.class, () -> service.execute("workspace", second.id(), requestId, true)).status());
        verify(client, times(2)).read(eq(config), any(), any(), any());
    }

    @Test
    void receiptCannotCrossExpiryWhileCheckingConnection() {
        var preview = service.preview("workspace", query());
        service.execute("workspace", preview.id(), requestId, true);
        when(connections.require("workspace", revision)).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(now.plusSeconds(600));
            return config;
        });
        assertEquals(409, assertThrows(LibraryException.class, () -> service.result("workspace", preview.id())).status());
    }

    @Test
    void cancellationAfterProviderReturnsStillDiscardsOutputAndReplaysOnlyCancellation() {
        var preview = service.preview("workspace", query());
        when(client.read(eq(config), any(), any(), any())).thenAnswer(invocation -> {
            service.cancel("workspace", preview.id());
            return new DatadogLogsClient.Result("private provider output", 1, false);
        });
        var cancelled = service.execute("workspace", preview.id(), requestId, true);
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("", cancelled.output());
        assertEquals(cancelled, service.execute("workspace", preview.id(), requestId, true));
        verify(client).read(eq(config), any(), any(), any());
    }

    @Test
    void revokedCompletedReceiptCannotBeRecoveredOrReplayed() {
        var preview = service.preview("workspace", query());
        service.execute("workspace", preview.id(), requestId, true);
        when(connections.require("workspace", revision)).thenThrow(new LibraryException(409, "revoked"));
        assertThrows(LibraryException.class, () -> service.result("workspace", preview.id()));
        assertThrows(LibraryException.class, () -> service.execute("workspace", preview.id(), requestId, true));
        verify(client).read(eq(config), any(), any(), any());
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
