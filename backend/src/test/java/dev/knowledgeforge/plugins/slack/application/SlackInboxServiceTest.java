package dev.knowledgeforge.plugins.slack.application;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.plugins.slack.domain.SlackEvent;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackInboxServiceTest {
    final SlackProvider provider = mock(SlackProvider.class);
    final LibraryStore library = mock(LibraryStore.class);
    final ChatThreadService threads = mock(ChatThreadService.class);
    final SlackProvider.Session session = mock(SlackProvider.Session.class);
    final AtomicReference<SlackProvider.Listener> listener = new AtomicReference<>();
    final SlackInboxStore store = new MemorySlackInboxStore();
    final SlackReconnectScheduler scheduler = mock(SlackReconnectScheduler.class);
    final SlackInboxService service = new SlackInboxService(provider, library, threads, store, scheduler);
    final SlackInboxService.Credentials credentials = new SlackInboxService.Credentials("xapp-synthetic", "xoxb-synthetic", true);

    @BeforeEach
    void setup() {
        when(library.workspace("one")).thenReturn(Optional.of(new Workspace("one", "Synthetic one", "now")));
        when(library.workspace("two")).thenReturn(Optional.of(new Workspace("two", "Synthetic two", "now")));
        when(provider.authenticate("xoxb-synthetic")).thenReturn(new SlackProvider.Identity("TTEAM", "UBOT"));
        when(provider.open(eq("xapp-synthetic"), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            return session;
        });
    }

    void connected() {
        assertThat(service.connect(credentials).state()).isEqualTo("CONNECTING");
        listener.get().ready();
        assertThat(service.status().state()).isEqualTo("CONNECTED");
        service.route("CCHANNEL", "one");
    }

    SlackEvent event(String id, String timestamp) {
        return new SlackEvent(id, "TTEAM", "CCHANNEL", "UHUMAN", timestamp, "Synthetic Strategy question");
    }

    @Test
    void notConfiguredAndReadOperationsNeverConnectOrGenerate() {
        assertThat(service.status().state()).isEqualTo("NOT_CONFIGURED");
        assertThat(service.status().sessionOnly()).isFalse();
        assertThat(service.status().tokensSessionOnly()).isTrue();
        assertThat(service.status().channelPermissions()).isEqualTo("NOT_CHECKED");
        assertThat(service.routes()).isEmpty();
        assertThat(service.inbox("one")).isEmpty();
        verifyNoInteractions(provider, threads);
        assertThatThrownBy(() -> service.route("CCHANNEL", "one")).isInstanceOf(LibraryException.class);
    }

    @Test
    void credentialsRequireExplicitConsentAndNeverAppearInErrors() {
        for (var invalid : List.of(new SlackInboxService.Credentials("xapp-synthetic", "xoxb-synthetic", false),
                new SlackInboxService.Credentials("xapp-secret\r\nInjected: yes", "xoxb-synthetic", true),
                new SlackInboxService.Credentials("https://example.invalid", "xoxb-synthetic", true))) {
            assertThatThrownBy(() -> service.connect(invalid)).isInstanceOf(LibraryException.class)
                    .hasMessageNotContaining("secret").hasMessageNotContaining("Injected");
        }
        verifyNoInteractions(provider);
        when(provider.authenticate(anyString())).thenThrow(new IllegalStateException("secret-token"));
        assertThatThrownBy(() -> service.connect(credentials)).isInstanceOf(LibraryException.class)
                .hasMessageNotContaining("secret-token").hasCause(null);
        assertThat(service.status().state()).isEqualTo("CONNECTION_FAILED");
        assertThat(service.status().teamId()).isNull();
        assertThat(credentials.toString()).doesNotContain("xapp-synthetic", "xoxb-synthetic");
    }

    @Test
    void routeAndDedupeAreIndependentOfActiveWorkspaceAndEnvelopeRetries() {
        connected();
        var first = event("EvFIRST", "1800000000.000001");
        assertThat(listener.get().event(first)).isTrue();
        assertThat(listener.get().event(first)).isTrue();
        assertThat(listener.get().event(event("EvMENTION", first.timestamp()))).isTrue();
        service.route("CCHANNEL", "two");
        assertThat(listener.get().event(first)).isTrue();
        assertThat(service.inbox("one")).hasSize(1);
        assertThat(service.inbox("two")).isEmpty();
        assertThat(listener.get().event(event("EvSECOND", "1800000000.000002"))).isTrue();
        assertThat(service.inbox("two")).hasSize(1);
        verifyNoInteractions(threads);
        verify(library, never()).activeWorkspace();
    }

    @Test
    void otherTeamsBotsAndUnroutedChannelsAreIgnored() {
        connected();
        assertThat(listener.get().event(new SlackEvent("EvOTHER", "TOTHER", "CCHANNEL", "UHUMAN", "1800000000.000001", "Synthetic"))).isTrue();
        assertThat(listener.get().event(new SlackEvent("EvBOT", "TTEAM", "CCHANNEL", "UBOT", "1800000000.000002", "Synthetic"))).isTrue();
        assertThat(listener.get().event(new SlackEvent("EvCHANNEL", "TTEAM", "COTHER", "UHUMAN", "1800000000.000003", "Synthetic"))).isTrue();
        assertThat(service.inbox("one")).isEmpty();
        assertThat(service.status().ignoredEvents()).isEqualTo(3);
        verifyNoInteractions(threads);
    }

    @Test
    void deletionKeepsDedupeAndInboxCapacityNeverEvictsUnsentMessages() {
        connected();
        var first = event("EvFIRST", "1800000000.000001");
        listener.get().event(first);
        service.remove("one", service.inbox("one").getFirst().id());
        assertThat(listener.get().event(first)).isTrue();
        assertThat(service.inbox("one")).isEmpty();
        for (int index = 0; index < SlackInboxService.MAX_ITEMS; index++) {
            assertThat(listener.get().event(event("Ev" + index, "1800000001." + String.format("%06d", index)))).isTrue();
        }
        var retained = service.inbox("one");
        assertThat(listener.get().event(event("EvOVERFLOW", "1800000002.000001"))).isFalse();
        listener.get().closed();
        assertThat(service.status().state()).isEqualTo("INBOX_FULL");
        assertThat(service.inbox("one")).isEqualTo(retained);
        verifyNoInteractions(threads);
    }

    @Test
    void explicitSendCreatesOnlyOneSavedQuestionAndNeverTrustsPayloadWorkspace() {
        connected();
        listener.get().event(event("EvFIRST", "1800000000.000001"));
        var item = service.inbox("one").getFirst();
        var context = new EvidenceBundle.ChatContext("thread", null, "slack-" + item.id(), "Strategy", List.of());
        var bundle = new EvidenceBundle("bundle", "one", "now", "digest",
                new EvidenceBundle.Payload("Strategy", List.of(), false, "NO_EVIDENCE", 0, 12000, List.of(), List.of(), context));
        when(threads.create(eq("one"), any())).thenReturn(bundle);
        assertThatThrownBy(() -> service.send("two", item.id(), "Strategy")).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> service.send("one", item.id(), "x".repeat(257))).isInstanceOf(LibraryException.class);
        verifyNoInteractions(threads);
        var sent = service.send("one", item.id(), " Strategy ");
        assertThat(sent).isEqualTo(new SlackInboxService.Sent("thread", "bundle"));
        assertThat(service.send("one", item.id(), "Strategy")).isEqualTo(sent);
        assertThatThrownBy(() -> service.send("one", item.id(), "different")).isInstanceOf(LibraryException.class);
        var request = ArgumentCaptor.forClass(ChatThreadService.Create.class);
        verify(threads).create(eq("one"), request.capture());
        assertThat(request.getValue()).isEqualTo(new ChatThreadService.Create("slack-" + item.id(), null, null, "Strategy", List.of()));
        verifyNoMoreInteractions(threads);
    }

    @Test
    void credentialContentCannotBeAdoptedButInboxAndOriginalEventRemainUnchanged() {
        connected();
        var event = new SlackEvent("EvSECRET", "TTEAM", "CCHANNEL", "UHUMAN", "1800000000.000001", "token=Synthet1c-Only-Value-7823");
        listener.get().event(event);
        var original = service.inbox("one").getFirst();
        assertThatThrownBy(() -> service.send("one", original.id(), "Explain safely"))
                .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보").hasMessageNotContaining("Synthet1c-Only-Value-7823");
        assertThat(service.inbox("one").getFirst()).isEqualTo(original);
        assertThat(service.inbox("one").getFirst().event()).isEqualTo(event);
        verifyNoInteractions(threads);
    }

    @Test
    void credentialQuestionCannotBeAdoptedFromSafeSlackMessage() {
        connected();
        listener.get().event(event("EvSAFE", "1800000000.000001"));
        var original = service.inbox("one").getFirst();
        assertThatThrownBy(() -> service.send("one", original.id(), "password=Synthet1c-Only-Value-7823"))
                .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
        assertThat(service.inbox("one").getFirst()).isEqualTo(original);
        verifyNoInteractions(threads);
    }

    @Test
    void failedSendCanRetryWithSameIdAndDeletedWorkspaceNeverFallsBack() {
        connected();
        listener.get().event(event("EvFIRST", "1800000000.000001"));
        var item = service.inbox("one").getFirst();
        when(threads.create(eq("one"), any())).thenThrow(new LibraryException(503, "Synthetic temporary failure"));
        assertThatThrownBy(() -> service.send("one", item.id(), "Strategy")).isInstanceOf(LibraryException.class);
        assertThat(service.inbox("one").getFirst().sent()).isNull();
        assertThatThrownBy(() -> service.send("one", item.id(), "Strategy")).isInstanceOf(LibraryException.class);
        verify(threads, times(2)).create("one", new ChatThreadService.Create("slack-" + item.id(), null, null, "Strategy", List.of()));
        when(library.workspace("one")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.send("one", item.id(), "Strategy")).isInstanceOf(LibraryException.class);
        verifyNoMoreInteractions(threads);
    }

    @Test
    void disconnectRejectsLateCallbacksAndDoesNotReconnectAutomatically() {
        connected();
        var old = listener.get();
        service.disconnect();
        old.ready(); old.closed();
        assertThat(old.event(event("EvLATE", "1800000000.000001"))).isFalse();
        assertThat(service.status().state()).isEqualTo("DISCONNECTED");
        verify(session).close();
        verify(provider).authenticate("xoxb-synthetic");
        verify(provider).open(eq("xapp-synthetic"), any());
        verifyNoMoreInteractions(provider);
    }

    @Test
    void disconnectDuringAuthenticationDoesNotOpenSocketAfterwards() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(provider.authenticate(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new SlackProvider.Identity("TTEAM", "UBOT");
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var connecting = executor.submit(() -> service.connect(credentials));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                service.disconnect();
                release.countDown();
                assertThat(connecting.get(5, TimeUnit.SECONDS).state()).isEqualTo("DISCONNECTED");
                verify(provider, never()).open(anyString(), any());
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    @Test
    void newServiceRestoresRoutesAndInboxButNeverTokensOrConnectedStatus() {
        connected();
        listener.get().event(event("EvFIRST", "1800000000.000001"));
        var restarted = new SlackInboxService(provider, library, threads, store, scheduler);
        assertThat(restarted.status().state()).isEqualTo("NOT_CONFIGURED");
        assertThat(restarted.routes()).hasSize(1);
        assertThat(restarted.inbox("one")).hasSize(1);
    }

    @Test
    void boundedReconnectReauthenticatesAndStopsAfterThreeFailuresWithoutSleeps() {
        var tasks = new java.util.ArrayDeque<Runnable>();
        when(scheduler.schedule(any(), anyInt())).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return mock(SlackReconnectScheduler.Pending.class);
        });
        connected();
        listener.get().closed();
        assertThat(service.status().state()).isEqualTo("RECONNECTING");
        assertThat(listener.get().event(event("EvGAP", "1800000000.1"))).isFalse();
        when(provider.authenticate(anyString())).thenThrow(new IllegalStateException("synthetic offline"));
        for (int attempt = 0; attempt < 3; attempt++) tasks.removeFirst().run();
        assertThat(tasks).isEmpty();
        assertThat(service.status().state()).isEqualTo("RECONNECT_REQUIRED");
        verify(provider, times(4)).authenticate(anyString());
        verify(scheduler).schedule(any(), eq(1));
        verify(scheduler).schedule(any(), eq(2));
        verify(scheduler).schedule(any(), eq(4));
    }

    @Test
    void disconnectCancelsScheduledReconnectEvenIfTaskRunsLate() {
        var task = new AtomicReference<Runnable>();
        var pending = mock(SlackReconnectScheduler.Pending.class);
        when(scheduler.schedule(any(), anyInt())).thenAnswer(invocation -> { task.set(invocation.getArgument(0)); return pending; });
        connected();
        listener.get().closed();
        service.disconnect();
        task.get().run();
        verify(pending).cancel();
        verify(provider, times(1)).authenticate(anyString());
        assertThat(service.status().state()).isEqualTo("DISCONNECTED");
    }

    @Test
    void reconnectAuthRevocationIsTerminalAndNeverRetried() {
        var task = new AtomicReference<Runnable>();
        when(scheduler.schedule(any(), anyInt())).thenAnswer(invocation -> {
            task.set(invocation.getArgument(0)); return mock(SlackReconnectScheduler.Pending.class);
        });
        connected();
        listener.get().closed();
        when(provider.authenticate(anyString())).thenThrow(new SlackProvider.AccessFailure(SlackProvider.FailureKind.AUTH_REQUIRED));
        task.get().run();
        assertThat(service.status().state()).isEqualTo("AUTH_REQUIRED");
        verify(scheduler, times(1)).schedule(any(), anyInt());
    }

    @Test
    void accessLossStopsCurrentTeamOnlyAndLateCallbacksCannotRestoreConnected() {
        connected();
        var active = listener.get();
        active.accessLost("TOTHER", null);
        active.accessLost("TTEAM", "UOTHER");
        assertThat(service.status().state()).isEqualTo("CONNECTED");
        active.accessLost("TTEAM", "UBOT");
        active.ready(); active.closed();
        assertThat(service.status().state()).isEqualTo("PERMISSION_DENIED");
        assertThat(service.status().channelPermissions()).isEqualTo("DENIED");
        assertThat(active.event(event("EvLATE", "1800000000.1"))).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    void deletionsCannotBeResurrectedAndCountsDoNotExposeMessages() {
        connected();
        listener.get().event(event("EvROOT", "1800000000.1"));
        var item = service.inbox("one").getFirst();
        assertThat(service.indicator("one")).isEqualTo(new SlackInboxService.Indicator("CONNECTED", 1));
        assertThat(service.indicator("two").count()).isZero();
        listener.get().event(new SlackEvent("EvDELETE", "TTEAM", "CCHANNEL", null, "1800000000.1", "",
                "1800000000.1", "1800000002.1", true));
        listener.get().event(event("EvLATE", "1800000000.100000"));
        assertThat(service.inbox("one").getFirst().id()).isEqualTo(item.id());
        assertThat(service.inbox("one").getFirst().event().deleted()).isTrue();
        assertThat(service.indicator("one").count()).isZero();
        assertThatThrownBy(() -> service.send("one", item.id(), "Synthetic")).hasMessageContaining("삭제");
        verifyNoInteractions(threads);
    }
}
