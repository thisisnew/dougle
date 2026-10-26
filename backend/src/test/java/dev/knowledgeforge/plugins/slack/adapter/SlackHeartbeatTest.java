package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackProvider;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class SlackHeartbeatTest {
    final AtomicLong nanos = new AtomicLong();
    final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    final SlackProvider.Listener listener = mock(SlackProvider.Listener.class);
    final WebSocket socket = mock(WebSocket.class);
    SlackSocketProvider provider;
    SlackSocketProvider.Frames frames;
    ByteBuffer latestProbe;

    @BeforeEach void setup() {
        doReturn(mock(ScheduledFuture.class)).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        when(socket.sendPing(any())).thenAnswer(invocation -> {
            latestProbe = ((ByteBuffer) invocation.getArgument(0)).duplicate();
            return CompletableFuture.completedFuture(socket);
        });
        provider = new SlackSocketProvider(mock(SlackHttp.class), scheduler, nanos::get);
        frames = provider.new Frames(listener);
        frames.onOpen(socket);
        frames.onText(socket, "{\"type\":\"hello\"}", true);
    }

    @AfterEach void close() { frames.finish(socket); provider.close(); }

    void tick(long seconds) {
        nanos.set(TimeUnit.SECONDS.toNanos(seconds));
        frames.heartbeatTick(socket);
    }

    @Test void quietChannelStaysConnectedWhenMatchingPongsArrive() {
        tick(29);
        verify(socket, never()).sendPing(any());
        tick(30);
        frames.onPong(socket, latestProbe.duplicate());
        tick(60);
        frames.onPong(socket, latestProbe.duplicate());
        tick(90);
        frames.onPong(socket, latestProbe.duplicate());
        verify(socket, times(3)).sendPing(any());
        verify(listener, never()).closed();
        verify(listener, never()).event(any());
        verify(socket, never()).sendText(anyString(), anyBoolean());
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(1L), eq(1L), eq(TimeUnit.SECONDS));
    }

    @Test void missingOrUnrelatedPongClosesOnceAndLateCallbacksCannotReviveSocket() {
        tick(30);
        frames.onPong(socket, ByteBuffer.wrap(new byte[] { 1 }));
        tick(39);
        verify(listener, never()).closed();
        tick(40);
        frames.onPong(socket, latestProbe.duplicate());
        tick(100);
        verify(listener).closed();
        verify(socket).abort();
        verify(heartbeat).cancel(false);
        verify(socket, times(1)).sendPing(any());
        verify(listener, times(1)).ready();
    }

    @Test void stalledPingSendClosesAtFiveSecondsWithoutSleeping() {
        doReturn(new CompletableFuture<WebSocket>()).when(socket).sendPing(any());
        tick(30);
        tick(34);
        verify(listener, never()).closed();
        tick(35);
        verify(listener).closed();
        verify(socket).abort();
    }

    @Test void lateSendCompletionCannotExtendExpiredSendDeadline() {
        var send = new CompletableFuture<WebSocket>();
        doReturn(send).when(socket).sendPing(any());
        tick(30);
        nanos.set(TimeUnit.SECONDS.toNanos(36));
        send.complete(socket);
        verify(listener).closed();
        verify(socket).abort();
    }

    @Test void latePongCannotReviveSocketAfterSuspendEvenBeforeWatchdogRuns() {
        tick(30);
        nanos.set(TimeUnit.SECONDS.toNanos(90));
        frames.onPong(socket, latestProbe.duplicate());
        verify(listener).closed();
        verify(socket).abort();
    }

    @Test void failedPingSendClosesOnce() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic"))).when(socket).sendPing(any());
        tick(30);
        tick(60);
        verify(listener).closed();
        verify(socket).abort();
        verify(socket, times(1)).sendPing(any());
    }

    @Test void failedResponseToServerPingClosesSocket() {
        when(socket.sendPong(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic")));
        frames.onPing(socket, ByteBuffer.wrap(new byte[] { 2 }));
        verify(listener).closed();
        verify(socket).abort();
    }

    @Test void synchronousPingFailureClosesSocket() {
        doThrow(new IllegalStateException("synthetic")).when(socket).sendPing(any());
        tick(30);
        verify(listener).closed();
        verify(socket).abort();
    }

    @Test void closeCancelsWatchdogAndIgnoresPendingSendCompletionAndProtocolFrames() {
        var send = new CompletableFuture<WebSocket>();
        doReturn(send).when(socket).sendPing(any());
        tick(30);
        frames.finish(socket);
        send.complete(socket);
        frames.onPing(socket, ByteBuffer.wrap(new byte[] { 3 }));
        frames.onPong(socket, ByteBuffer.wrap(new byte[] { 3 }));
        tick(100);
        verify(heartbeat).cancel(false);
        verify(listener).closed();
        verify(socket).abort();
        verify(socket, times(1)).sendPing(any());
        verify(socket, never()).sendPong(any());
        verify(socket, never()).sendText(anyString(), anyBoolean());
    }

    @Test void serverPingResponseIsProtocolTrafficNotMessagePosting() {
        var ping = ByteBuffer.wrap(new byte[] { 4 });
        when(socket.sendPong(any())).thenReturn(CompletableFuture.completedFuture(socket));
        frames.onPing(socket, ping).toCompletableFuture().join();
        verify(socket).sendPong(ping);
        verify(socket, never()).sendText(anyString(), anyBoolean());
        verify(listener, never()).event(any());
        verify(listener, never()).closed();
    }
}
