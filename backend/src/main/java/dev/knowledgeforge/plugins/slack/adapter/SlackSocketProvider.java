package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.plugins.slack.application.SlackProvider;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class SlackSocketProvider implements SlackProvider {
    private final SlackHttp http;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService deadlines;
    private final java.util.function.LongSupplier nanoTime;

    @org.springframework.beans.factory.annotation.Autowired
    public SlackSocketProvider(SlackHttp http) {
        this(http, Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory()), System::nanoTime);
    }

    SlackSocketProvider(SlackHttp http, java.util.concurrent.ScheduledExecutorService deadlines,
                        java.util.function.LongSupplier nanoTime) {
        this.http = http; this.deadlines = deadlines; this.nanoTime = nanoTime;
    }

    @Override public Identity authenticate(String botToken) {
        var result = http.post("auth.test", botToken);
        if (!result.path("team_id").isString() || !result.path("user_id").isString() || !result.path("bot_id").isString()) {
            throw new LibraryException(502, "설치된 Slack 봇 인증이 필요합니다.");
        }
        return new Identity(result.path("team_id").asString(), result.path("user_id").asString());
    }

    static URI socketUri(String value) {
        try {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            var uri = URI.create(value);
            String host = uri.getHost();
            if (!"wss".equals(uri.getScheme()) || host == null || !host.matches("wss(?:-[a-z0-9-]+)?\\.slack\\.com")
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || !"/link/".equals(uri.getPath())
                    || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException exception) { throw new IllegalArgumentException("Invalid Slack socket endpoint"); }
    }

    @Override public Session open(String appToken, Listener listener) {
        try {
            var reply = http.post("apps.connections.open", appToken);
            if (!reply.path("url").isString()) throw new IllegalArgumentException();
            URI uri = socketUri(reply.path("url").asString());
            var frames = new Frames(listener);
            var pending = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, frames);
            WebSocket socket;
            try { socket = pending.get(10, TimeUnit.SECONDS); }
            catch (Exception exception) { pending.thenAccept(WebSocket::abort); pending.cancel(true); throw exception; }
            return () -> { frames.finish(socket); };
        } catch (AccessFailure exception) { throw exception; }
        catch (Exception exception) { throw new LibraryException(502, "Slack 수신 연결을 열지 못했습니다."); }
    }

    final class Frames implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder buffer = new StringBuilder();
        private boolean ready;
        private boolean closed;
        private ScheduledFuture<?> deadline;
        private ScheduledFuture<?> heartbeat;
        private long nextPing;
        private long probeDeadline;
        private ByteBuffer pendingPing;
        Frames(Listener listener) { this.listener = listener; }

        @Override public synchronized void onOpen(WebSocket socket) {
            sockets.add(socket);
            deadline = deadlines.schedule(() -> finish(socket), 10, TimeUnit.SECONDS);
            socket.request(1);
        }

        synchronized void finish(WebSocket socket) {
            if (closed) return;
            closed = true;
            if (deadline != null) deadline.cancel(false);
            if (heartbeat != null) heartbeat.cancel(false);
            pendingPing = null;
            buffer.setLength(0);
            sockets.remove(socket);
            socket.abort();
            listener.closed();
        }

        @Override public synchronized CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (closed) return CompletableFuture.completedFuture(null);
            try {
                if (data.length() > SlackEnvelopeParser.MAX_BYTES - buffer.length()) throw new IllegalArgumentException();
                if (ready && buffer.isEmpty()) deadline = deadlines.schedule(() -> finish(socket), 10, TimeUnit.SECONDS);
                buffer.append(data);
                if (!last) { socket.request(1); return CompletableFuture.completedFuture(null); }
                var envelope = SlackEnvelopeParser.parse(buffer.toString());
                buffer.setLength(0);
                if (deadline != null) deadline.cancel(false);
                if (envelope.type().equals("hello")) {
                    if (ready) throw new IllegalArgumentException();
                    ready = true;
                    nextPing = nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(30);
                    heartbeat = deadlines.scheduleAtFixedRate(() -> heartbeatTick(socket), 1, 1, TimeUnit.SECONDS);
                    listener.ready(); socket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
                if (!ready || envelope.type().equals("disconnect")) { finish(socket); return CompletableFuture.completedFuture(null); }
                if (envelope.type().equals("access_revoked") && listener.accessLost(envelope.teamId(), envelope.userId())) {
                    finish(socket);
                    return CompletableFuture.completedFuture(null);
                }
                if (envelope.event() != null && !listener.event(envelope.event())) { finish(socket); return CompletableFuture.completedFuture(null); }
                return socket.sendText("{\"envelope_id\":\"" + envelope.envelopeId() + "\"}", true)
                        .orTimeout(5, TimeUnit.SECONDS).whenComplete((sent, error) -> {
                            if (error != null) finish(socket);
                            else socket.request(1);
                        });
            } catch (RuntimeException exception) { finish(socket); return CompletableFuture.completedFuture(null); }
        }

        synchronized void heartbeatTick(WebSocket socket) {
            if (closed || !ready) return;
            long now = nanoTime.getAsLong();
            if (pendingPing != null) {
                if (now - probeDeadline >= 0) finish(socket);
                return;
            }
            if (now - nextPing < 0) return;
            var probe = ByteBuffer.wrap(java.util.UUID.randomUUID().toString()
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII)).asReadOnlyBuffer();
            pendingPing = probe;
            probeDeadline = now + TimeUnit.SECONDS.toNanos(5);
            try {
                socket.sendPing(probe.duplicate()).whenComplete((sent, error) -> {
                    synchronized (Frames.this) {
                        if (closed) return;
                        if (error != null) { finish(socket); return; }
                        if (pendingPing == probe) {
                            long completed = nanoTime.getAsLong();
                            if (completed - probeDeadline >= 0) { finish(socket); return; }
                            probeDeadline = completed + TimeUnit.SECONDS.toNanos(10);
                        }
                    }
                });
            } catch (RuntimeException failure) { finish(socket); }
        }

        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) { finish(socket); return CompletableFuture.completedFuture(null); }
        @Override public synchronized CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            if (closed) return CompletableFuture.completedFuture(null);
            socket.request(1);
            try {
                return socket.sendPong(message).orTimeout(5, TimeUnit.SECONDS).whenComplete((sent, error) -> {
                    if (error != null) finish(socket);
                });
            } catch (RuntimeException failure) {
                finish(socket);
                return CompletableFuture.completedFuture(null);
            }
        }
        @Override public synchronized CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (pendingPing != null && pendingPing.equals(message)) {
                if (nanoTime.getAsLong() - probeDeadline >= 0) {
                    finish(socket);
                    return CompletableFuture.completedFuture(null);
                }
                pendingPing = null;
                nextPing = nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(30);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { finish(socket); return CompletableFuture.completedFuture(null); }
        @Override public void onError(WebSocket socket, Throwable error) { finish(socket); }
    }

    @PreDestroy public void close() {
        sockets.forEach(WebSocket::abort);
        sockets.clear(); deadlines.shutdownNow(); client.shutdownNow();
    }
}
