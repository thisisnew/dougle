package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackProvider;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackTransportTest {
    String envelope(String event) {
        return "{\"type\":\"events_api\",\"envelope_id\":\"envelope-1\",\"payload\":{\"type\":\"event_callback\",\"team_id\":\"TTEAM\",\"event_id\":\"EvFIRST\",\"event\":" + event + "}}";
    }
    String message() { return "{\"type\":\"message\",\"user\":\"UHUMAN\",\"channel\":\"CCHANNEL\",\"ts\":\"1800000000.000001\",\"text\":\"Synthetic <script>ignored()</script> https://example.invalid\"}"; }

    @Test
    void parsesOnlySupportedHumanEventsAndKeepsTextInert() {
        var parsed = SlackEnvelopeParser.parse(envelope(message()));
        assertThat(parsed.envelopeId()).isEqualTo("envelope-1");
        assertThat(parsed.event().teamId()).isEqualTo("TTEAM");
        assertThat(parsed.event().text()).contains("<script>", "https://example.invalid");
        assertThat(SlackEnvelopeParser.parse(envelope(message().replace("\"message\"", "\"app_mention\""))).event()).isNotNull();
        for (String extra : new String[] { "\"subtype\":\"message_changed\",", "\"bot_id\":\"BBOT\",", "\"hidden\":true," }) {
            assertThat(SlackEnvelopeParser.parse(envelope("{" + extra + message().substring(1))).event()).isNull();
        }
        assertThat(SlackEnvelopeParser.parse(envelope(message().replace("\"UHUMAN\"", "42"))).event()).isNull();
        assertThat(SlackEnvelopeParser.parse(envelope(message().replace("Synthetic", "x".repeat(12001)))).event()).isNull();
    }

    @Test
    void rejectsOversizedDuplicateAndTrailingEnvelopeDataWithoutEchoing() {
        for (String raw : new String[] { "secret-token", " ".repeat(65537), envelope(message()) + "{}",
                "{\"type\":\"hello\",\"type\":\"hello\"}",
                envelope(message()).replace("envelope-1", "secret\\\"token") }) {
            assertThatThrownBy(() -> SlackEnvelopeParser.parse(raw)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid Slack envelope").hasCause(null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://wss.slack.com/link/?ticket=secret", "ws://wss.slack.com/link/?ticket=secret",
            "wss://wss.slack.com.evil.invalid/link/?ticket=secret", "wss://127.0.0.1/link/?ticket=secret",
            "wss://user:secret@wss.slack.com/link/?ticket=secret", "wss://wss.slack.com:8443/link/?ticket=secret",
            "wss://wss.slack.com/other?ticket=secret", "wss://wss.slack.com/link/?ticket=secret#fragment",
            "wss://wss.slack.com/link/", "wss://slack.com/link/?ticket=secret" })
    void rejectsUntrustedSocketEndpoints(String value) {
        assertThatThrownBy(() -> SlackSocketProvider.socketUri(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Slack socket endpoint").hasCause(null);
    }

    @Test
    void permitsOnlySlackSocketHostsAndVerifiedBotIdentity() {
        assertThat(SlackSocketProvider.socketUri("wss://wss-primary.slack.com/link/?ticket=synthetic").getHost()).isEqualTo("wss-primary.slack.com");
        var http = mock(SlackHttp.class);
        var provider = new SlackSocketProvider(http);
        try {
            when(http.post("auth.test", "xoxb-synthetic")).thenReturn(new JsonMapper().readTree("{\"ok\":true,\"team_id\":\"TTEAM\",\"user_id\":\"UBOT\",\"bot_id\":\"BBOT\"}"));
            assertThat(provider.authenticate("xoxb-synthetic")).isEqualTo(new SlackProvider.Identity("TTEAM", "UBOT"));
            verify(http).post("auth.test", "xoxb-synthetic");
        } finally { provider.close(); }
    }

    @Test
    void socketAcknowledgesEachEnvelopeOnlyAfterInboxAcceptance() {
        var provider = new SlackSocketProvider(mock(SlackHttp.class));
        var listener = mock(SlackProvider.Listener.class);
        var socket = mock(WebSocket.class);
        when(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        when(listener.event(any())).thenReturn(true);
        try {
            var frames = provider.new Frames(listener);
            frames.onOpen(socket);
            verify(listener, never()).ready();
            frames.onText(socket, "{\"type\":\"hello\"}", true);
            verify(listener).ready();
            String raw = envelope(message());
            frames.onText(socket, raw.substring(0, 20), false);
            verify(listener, never()).event(any());
            frames.onText(socket, raw.substring(20), true).toCompletableFuture().join();
            verify(listener).event(any());
            verify(socket).sendText("{\"envelope_id\":\"envelope-1\"}", true);
            when(listener.event(any())).thenReturn(false);
            frames.onText(socket, raw, true);
            verify(socket).abort();
            verify(listener).closed();
            verify(socket, times(1)).sendText(anyString(), eq(true));
        } finally { provider.close(); }
    }

    @Test
    void disconnectAndOversizedFragmentsFailClosedWithoutFetchingAnything() {
        var http = mock(SlackHttp.class);
        var provider = new SlackSocketProvider(http);
        try {
            for (String raw : new String[] { "x".repeat(65537), "{\"type\":\"disconnect\"}" }) {
                var socket = mock(WebSocket.class);
                var listener = mock(SlackProvider.Listener.class);
                var frames = provider.new Frames(listener);
                frames.onOpen(socket);
                frames.onText(socket, raw, true);
                verify(socket).abort();
                verify(listener).closed();
                verify(listener, never()).event(any());
            }
            verifyNoInteractions(http);
        } finally { provider.close(); }
    }

    @Test
    void parsesThreadEditsAndMinimalDeletionWithoutInventingAuthor() {
        var edited = SlackEnvelopeParser.parse(envelope("""
                {"type":"message","subtype":"message_changed","hidden":true,"channel":"CCHANNEL",
                 "event_ts":"1800000003.1","message":{"type":"message","user":"UHUMAN",
                 "ts":"1800000001.1","thread_ts":"1800000000.1","text":"Corrected synthetic",
                 "edited":{"user":"UHUMAN","ts":"1800000002.1"}}}
                """)).event();
        assertThat(edited.text()).isEqualTo("Corrected synthetic");
        assertThat(edited.threadTimestamp()).isEqualTo("1800000000.1");
        assertThat(edited.revisionTimestamp()).isEqualTo("1800000002.1");
        var deleted = SlackEnvelopeParser.parse(envelope("""
                {"type":"message","subtype":"message_deleted","hidden":true,"channel":"CCHANNEL",
                 "deleted_ts":"1800000001.1","ts":"1800000004.1"}
                """)).event();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.userId()).isNull();
        assertThat(deleted.text()).isEmpty();
        assertThat(deleted.timestamp()).isEqualTo("1800000001.1");
    }

    @Test
    void relevantRevocationStopsSocketButUnrelatedMemberChangesAreAcknowledged() {
        var provider = new SlackSocketProvider(mock(SlackHttp.class));
        var socket = mock(WebSocket.class);
        var listener = mock(SlackProvider.Listener.class);
        when(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        try {
            var frames = provider.new Frames(listener);
            frames.onOpen(socket);
            frames.onText(socket, "{\"type\":\"hello\"}", true);
            frames.onText(socket, envelope("{\"type\":\"member_left_channel\",\"user\":\"UOTHER\"}"), true);
            verify(socket, never()).abort();
            verify(socket).sendText(anyString(), eq(true));
            when(listener.accessLost("TTEAM", null)).thenReturn(true);
            frames.onText(socket, envelope("{\"type\":\"tokens_revoked\"}"), true);
            verify(socket).abort();
            verify(listener).closed();
            verify(socket, times(1)).sendText(anyString(), eq(true));
        } finally { provider.close(); }
    }
}
