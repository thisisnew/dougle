package dev.knowledgeforge.plugins.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.knowledgeforge.investigation.adapter.DatadogInvestigationController;
import dev.knowledgeforge.investigation.adapter.DatadogInvestigationService;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class DatadogInvestigationHttpTest {
    @TempDir Path root;

    @Test
    @Timeout(15)
    void configPreviewExecuteReceiptThroughMvcAndSyntheticLoopbackProvider() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var providerBody = new AtomicReference<String>();
        var providerKey = new AtomicReference<String>();
        server.createContext("/api/v2/logs/events/search", exchange -> {
            calls.incrementAndGet();
            providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            providerKey.set(exchange.getRequestHeaders().getFirst("DD-API-KEY"));
            byte[] body = """
                    {"data":[{"id":"fixture","attributes":{"timestamp":"2026-10-25T11:59:00Z",
                    "service":"orders","tags":["env:test"],"host":"private-host","message":"password=fixture-secret synthetic_api_key_0000"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try (var client = new DatadogLogsClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v2/logs/events/search"))) {
            var library = mock(LibraryStore.class);
            when(library.workspace("workspace")).thenReturn(Optional.of(new Workspace("workspace", "fixture", "now")));
            var connections = new DatadogConnections(new PluginVault(root.toString()), library);
            var service = new DatadogInvestigationService(connections, client, Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC));
            var mvc = MockMvcBuilders.standaloneSetup(new DatadogController(connections), new DatadogInvestigationController(service)).build();
            var json = new JsonMapper();
            String saved = mvc.perform(put("/api/plugins/datadog/workspace").contentType(MediaType.APPLICATION_JSON).content("""
                    {"site":"EU1","apiKey":"synthetic_api_key_0000","applicationKey":"synthetic_app_key_0000"}
                    """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(saved.contains("synthetic_api_key"));
            String revision = json.readTree(saved).path("revision").asText();
            String base = "/api/workspaces/workspace/datadog-investigations";
            String preview = mvc.perform(post(base + "/preview").contentType(MediaType.APPLICATION_JSON).content("""
                    {"connectionRevision":"%s","environment":"test","service":"orders","indexes":["fixture"],
                    "from":"2026-10-25T11:50:00Z","to":"2026-10-25T12:00:00Z","limit":20}
                    """.formatted(revision))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(0, calls.get());
            String id = json.readTree(preview).path("id").asText();
            String start = "{\"requestId\":\"synthetic-request-1234\",\"approved\":true}";
            String result = mvc.perform(post(base + "/" + id + "/execute").contentType(MediaType.APPLICATION_JSON).content(start))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("OK", json.readTree(result).path("status").asText());
            assertEquals(1, calls.get());
            assertEquals("synthetic_api_key_0000", providerKey.get());
            var filter = json.readTree(providerBody.get()).path("filter");
            assertEquals("env:\"test\" AND service:\"orders\"", filter.path("query").asText());
            assertEquals("fixture", filter.path("indexes").get(0).asText());
            for (String secret : java.util.List.of("fixture-secret", "synthetic_api_key_0000", "private-host")) assertFalse(result.contains(secret));
            String receipt = mvc.perform(get(base + "/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(json.readTree(result), json.readTree(receipt));
            mvc.perform(post(base + "/" + id + "/execute").contentType(MediaType.APPLICATION_JSON).content(start)).andExpect(status().isOk());
            mvc.perform(post(base + "/" + id + "/cancel")).andExpect(status().isNoContent());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
