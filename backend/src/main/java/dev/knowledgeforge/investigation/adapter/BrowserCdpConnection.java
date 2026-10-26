package dev.knowledgeforge.investigation.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class BrowserCdpConnection implements WebSocket.Listener, AutoCloseable {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<JsonNode> events = new ArrayBlockingQueue<>(128);
    private final StringBuilder message = new StringBuilder();
    private final WebSocket socket;
    private volatile boolean failed;

    BrowserCdpConnection(URI endpoint) throws Exception {
        if (!"ws".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())) throw new IOException("Local CDP required");
        try {
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
                    .buildAsync(endpoint, this).get(3, TimeUnit.SECONDS);
        } catch (Exception failure) {
            client.shutdownNow();
            throw failure;
        }
    }

    JsonNode command(String method, Map<String, ?> parameters, String session, long deadline) throws Exception {
        long remaining = Math.min(TimeUnit.SECONDS.toNanos(10), deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("Browser deadline");
        var response = dispatch(method, parameters, session).get(remaining, TimeUnit.NANOSECONDS);
        if (response.has("error")) throw new IOException("Browser command rejected");
        return response.path("result");
    }

    CompletableFuture<JsonNode> dispatch(String method, Map<String, ?> parameters, String session) throws Exception {
        if (failed) throw new IOException("Browser protocol closed");
        if (pending.size() >= 16) throw new IOException("Browser command limit");
        int id = sequence.incrementAndGet();
        var request = json.createObjectNode().put("id", id).put("method", method);
        request.set("params", json.valueToTree(parameters));
        if (session != null) request.put("sessionId", session);
        var result = new CompletableFuture<JsonNode>();
        pending.put(id, result);
        result.whenComplete((value, failure) -> pending.remove(id));
        socket.sendText(request.toString(), true).whenComplete((value, failure) -> {
            if (failure != null) result.completeExceptionally(new IOException("Browser send failed"));
        });
        return result;
    }

    JsonNode event() throws Exception {
        if (failed) throw new IOException("Browser protocol limit or disconnect");
        return events.poll(50, TimeUnit.MILLISECONDS);
    }

    public void onOpen(WebSocket webSocket) { webSocket.request(1); }

    public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            if (message.length() + data.length() > 1024 * 1024) throw new IOException("CDP message limit");
            message.append(data);
            if (last) {
                var received = json.readTree(message.toString());
                message.setLength(0);
                if (received.has("id")) {
                    var future = pending.get(received.path("id").asInt());
                    if (future != null) future.complete(received);
                } else if (!events.offer(received)) {
                    throw new IOException("CDP event limit");
                }
            }
            webSocket.request(1);
        } catch (Exception invalid) {
            fail();
            webSocket.abort();
        }
        return null;
    }

    public void onError(WebSocket webSocket, Throwable error) { fail(); }
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        fail();
        return null;
    }

    private void fail() {
        failed = true;
        pending.values().forEach(future -> future.completeExceptionally(new IOException("Browser protocol closed")));
    }

    public void close() {
        fail();
        socket.abort();
        client.shutdownNow();
    }
}
