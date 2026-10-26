package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.slack.application.SlackInboxService;
import dev.knowledgeforge.plugins.slack.application.SlackInboxStore;
import dev.knowledgeforge.plugins.slack.application.SlackProvider;
import dev.knowledgeforge.plugins.slack.application.SlackReconnectScheduler;
import dev.knowledgeforge.plugins.slack.domain.SlackEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class SlackInboxIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-slack-inbox-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired SlackInboxService service;
    @Autowired SlackInboxStore store;
    @Autowired LibraryService library;
    @Autowired LibraryStore libraryStore;
    @Autowired ChatThreadService threads;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SlackProvider provider;
    @MockitoBean SlackReconnectScheduler scheduler;
    final AtomicReference<SlackProvider.Listener> listener = new AtomicReference<>();
    final SlackInboxService.Credentials credentials = new SlackInboxService.Credentials("xapp-synthetic", "xoxb-synthetic", true);
    String workspace;

    @BeforeEach void prepare() {
        service.disconnect();
        jdbc.execute("DROP TRIGGER IF EXISTS synthetic_slack_failure");
        jdbc.update("DELETE FROM workspace");
        jdbc.update("DELETE FROM slack_inbox_seen");
        workspace = library.createWorkspace("Slack synthetic").id();
        when(provider.authenticate(anyString())).thenReturn(new SlackProvider.Identity("TTEAM", "UBOT"));
        when(provider.open(anyString(), any())).thenAnswer(invocation -> {
            listener.set(invocation.getArgument(1));
            return mock(SlackProvider.Session.class);
        });
        connect(service);
        service.route("CCHANNEL", workspace);
    }

    void connect(SlackInboxService target) { target.connect(credentials); listener.get().ready(); }
    SlackEvent event(String id, String timestamp, String thread, String revision, String text, boolean deleted) {
        return new SlackEvent(id, "TTEAM", "CCHANNEL", "UHUMAN", timestamp, text, thread, revision, deleted);
    }
    SlackEvent root(String id, String timestamp) { return event(id, timestamp, timestamp, timestamp, "Synthetic inquiry", false); }
    SlackInboxService restart() {
        service.disconnect();
        return new SlackInboxService(provider, libraryStore, threads, new JdbcSlackInboxStore(jdbc, transactions), scheduler);
    }

    @Test void restoresInboxRoutesReceiptsAndDedupeWithoutRestoringAuthentication() {
        var message = root("EvROOT", "1800000000.000001");
        listener.get().event(message);
        var item = service.inbox(workspace).getFirst();
        var sent = service.send(workspace, item.id(), "Synthetic inquiry");
        listener.get().event(event("EvEDIT", message.timestamp(), message.timestamp(), "1800000003.1", "Corrected synthetic", false));
        assertThat(service.inbox(workspace).getFirst().sentQuestion()).isEqualTo("Synthetic inquiry");
        var restarted = restart();
        assertThat(restarted.status().state()).isEqualTo("NOT_CONFIGURED");
        assertThat(restarted.status().teamId()).isNull();
        assertThat(restarted.routes()).hasSize(1);
        assertThat(restarted.inbox(workspace).getFirst().sent()).isEqualTo(sent);
        assertThat(restarted.send(workspace, item.id(), "Synthetic inquiry")).isEqualTo(sent);
        connect(restarted);
        assertThat(listener.get().event(message)).isTrue();
        assertThat(listener.get().event(root("EvDUPLICATE", "1800000000.000001"))).isTrue();
        assertThat(restarted.inbox(workspace)).hasSize(1);
        restarted.remove(workspace, item.id());
        restarted.disconnect();
        var again = new SlackInboxService(provider, libraryStore, threads, new JdbcSlackInboxStore(jdbc, transactions), scheduler);
        connect(again);
        listener.get().event(message);
        assertThat(again.inbox(workspace)).isEmpty();
        assertThat(threads.list(workspace)).hasSize(1);
        again.close();
    }

    @Test void editsArrivingBeforeOriginalAreOrderedAndNeverRolledBackByOldEvents() {
        listener.get().event(root("EvLATER", "1800000002.1"));
        listener.get().event(event("EvEDIT", "1800000000.1", "1800000000.1", "1800000003.1", "Corrected synthetic", false));
        listener.get().event(root("EvORIGINAL", "1800000000.100000"));
        assertThat(service.inbox(workspace)).extracting(item -> item.event().text()).containsExactly("Corrected synthetic", "Synthetic inquiry");
        var restarted = restart();
        connect(restarted);
        listener.get().event(event("EvOLDER", "1800000000.1", "1800000000.1", "1800000001.1", "Stale correction", false));
        assertThat(restarted.inbox(workspace).getFirst().event().text()).isEqualTo("Corrected synthetic");
        listener.get().event(event("EvDELETE", "1800000000.1", "1800000000.1", "1800000004.1", "", true));
        listener.get().event(root("EvLATE", "1800000000.1"));
        var deleted = restarted.inbox(workspace).getFirst();
        assertThat(deleted.event().deleted()).isTrue();
        assertThat(deleted.event().text()).isEmpty();
        assertThatThrownBy(() -> restarted.send(workspace, deleted.id(), "Synthetic")).hasMessageContaining("삭제");
        restarted.close();
    }

    @Test void slackThreadMappingSurvivesRestartAndDoesNotMergeSeparateThreadsWithSameText() {
        listener.get().event(root("EvROOT", "1800000000.1"));
        var original = service.send(workspace, service.inbox(workspace).getFirst().id(), "Synthetic inquiry");
        var restarted = restart();
        connect(restarted);
        listener.get().event(event("EvREPLY", "1800000001.1", "1800000000.1", "1800000001.1", "Synthetic followup", false));
        var reply = restarted.send(workspace, restarted.inbox(workspace).getLast().id(), "Synthetic followup");
        assertThat(reply.threadId()).isEqualTo(original.threadId());
        assertThat(threads.get(workspace, reply.threadId()).bundles()).hasSize(2);
        listener.get().event(root("EvSEPARATE", "1800000002.1"));
        var separate = restarted.send(workspace, restarted.inbox(workspace).getLast().id(), "Synthetic inquiry");
        assertThat(separate.threadId()).isNotEqualTo(original.threadId());
        assertThat(restarted.indicator(workspace).count()).isZero();
        restarted.close();
    }

    @Test void failedReceiptPersistenceRollsBackConversationAndCanRetryExactlyOnce() {
        listener.get().event(root("EvROOT", "1800000000.1"));
        var item = service.inbox(workspace).getFirst();
        jdbc.execute("CREATE TRIGGER synthetic_slack_failure BEFORE INSERT ON slack_inbox_thread BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        assertThatThrownBy(() -> service.send(workspace, item.id(), "Synthetic inquiry")).isInstanceOf(RuntimeException.class);
        assertThat(threads.list(workspace)).isEmpty();
        assertThat(service.inbox(workspace).getFirst().sent()).isNull();
        jdbc.execute("DROP TRIGGER synthetic_slack_failure");
        var saved = service.send(workspace, item.id(), "Synthetic inquiry");
        assertThat(service.send(workspace, item.id(), "Synthetic inquiry")).isEqualTo(saved);
        assertThat(threads.list(workspace)).hasSize(1);
    }

    record DeletedLink(SlackInboxService.Sent original, String itemId) {}
    DeletedLink deletedLink() {
        listener.get().event(root("EvROOT", "1800000000.1"));
        var original = service.send(workspace, service.inbox(workspace).getFirst().id(), "Synthetic inquiry");
        threads.delete(workspace, original.threadId());
        listener.get().event(event("EvFOLLOWUP", "1800000001.1", "1800000000.1", "1800000001.1", "Followup", false));
        return new DeletedLink(original, service.inbox(workspace).getLast().id());
    }

    @Test void deletedThreadRequiresExplicitRecoveryAndReplaysOneLocalReceipt() {
        var link = deletedLink();
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "Followup"))
                .isInstanceOfSatisfying(SlackInboxService.MissingThreadException.class,
                        failure -> assertThat(failure.expectedThreadId()).isEqualTo(link.original().threadId()));
        assertThat(threads.list(workspace)).isEmpty();
        var recovered = service.send(workspace, link.itemId(), "Followup", true, link.original().threadId());
        assertThat(recovered.threadId()).isNotEqualTo(link.original().threadId());
        assertThat(service.send(workspace, link.itemId(), "Followup", true, link.original().threadId())).isEqualTo(recovered);
        var restarted = restart();
        assertThat(restarted.send(workspace, link.itemId(), "Followup", true, link.original().threadId())).isEqualTo(recovered);
        assertThat(threads.list(workspace)).hasSize(1);
        assertThat(threads.get(workspace, recovered.threadId()).jobs()).isEmpty();
        assertThat(service.inbox(workspace).getFirst().sent()).isEqualTo(link.original());
        restarted.close();
    }

    @Test void recoveryRejectsStaleLinkAndDoesNotReplaceAnExistingConversation() {
        var link = deletedLink();
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "Followup", true, "wrong-thread"))
                .hasMessageContaining("변경");
        var recovered = service.send(workspace, link.itemId(), "Followup", true, link.original().threadId());
        listener.get().event(event("EvLATER", "1800000002.1", "1800000000.1", "1800000002.1", "Later", false));
        String later = service.inbox(workspace).getLast().id();
        assertThatThrownBy(() -> service.send(workspace, later, "Later", true, link.original().threadId())).hasMessageContaining("변경");
        assertThatThrownBy(() -> service.send(workspace, later, "Later", true, recovered.threadId())).hasMessageContaining("기존 대화");
        assertThat(threads.list(workspace)).hasSize(1);
        assertThat(threads.get(workspace, recovered.threadId()).bundles()).hasSize(1);
    }

    @Test void explicitRecoveryCommitsRealSpringTransactionWithoutRollbackOnlyMarker() {
        var link = deletedLink();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var recovered = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(transaction -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var saved = service.send(workspace, link.itemId(), "Followup", true, link.original().threadId());
            assertThat(transaction.isRollbackOnly()).isFalse();
            return saved;
        });
        assertThat(recovered).isNotNull();
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var freshStore = new JdbcSlackInboxStore(jdbc, transactions);
        var persisted = freshStore.read(data -> data.items().get(link.itemId()));
        assertThat(persisted.sent()).isEqualTo(recovered);
        assertThat(freshStore.<String>read(data -> data.threads().get(workspace + "/" + persisted.event().threadKey()).threadId()))
                .isEqualTo(recovered.threadId());
        assertThat(threads.get(workspace, recovered.threadId()).bundles()).hasSize(1);
        assertThat(threads.get(workspace, recovered.threadId()).jobs()).isEmpty();
        assertThat(service.send(workspace, link.itemId(), "Followup", true, link.original().threadId())).isEqualTo(recovered);
        assertThat(threads.list(workspace)).hasSize(1);
    }

    @Test void recoveryChecksOwnershipAndBothCredentialContentBoundaries() {
        var link = deletedLink();
        String other = library.createWorkspace("Other Slack").id();
        assertThatThrownBy(() -> service.send(other, link.itemId(), "Followup", true, link.original().threadId()))
                .isNotInstanceOf(SlackInboxService.MissingThreadException.class).hasMessageContaining("찾을 수 없습니다");
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "xoxb-synthetic123456789", true, link.original().threadId()))
                .hasMessageContaining("인증정보");
        listener.get().event(event("EvSECRET", "1800000001.1", "1800000000.1", "1800000003.1", "xoxb-synthetic123456789", false));
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "Safe question", true, link.original().threadId()))
                .hasMessageContaining("인증정보");
        assertThat(threads.list(workspace)).isEmpty();
        assertThat(service.inbox(workspace).getLast().sent()).isNull();
    }

    @Test void recoveryRollsBackNewConversationAndLinkWhenReceiptPersistenceFails() {
        var link = deletedLink();
        jdbc.execute("CREATE TRIGGER synthetic_slack_failure BEFORE INSERT ON slack_inbox_thread BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "Followup", true, link.original().threadId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(threads.list(workspace)).isEmpty();
        assertThat(service.inbox(workspace).getLast().sent()).isNull();
        jdbc.execute("DROP TRIGGER synthetic_slack_failure");
        assertThatThrownBy(() -> service.send(workspace, link.itemId(), "Followup"))
                .isInstanceOf(SlackInboxService.MissingThreadException.class);
        service.send(workspace, link.itemId(), "Followup", true, link.original().threadId());
        assertThat(threads.list(workspace)).hasSize(1);
    }

    @Test void routeChangesPreserveOldOwnershipAndWorkspaceDeletionCascades() {
        var original = root("EvROOT", "1800000000.1");
        listener.get().event(original);
        String other = library.createWorkspace("Slack other").id();
        service.route("CCHANNEL", other);
        listener.get().event(event("EvEDIT", "1800000000.1", "1800000000.1", "1800000001.1", "Correction", false));
        assertThat(service.inbox(other)).isEmpty();
        assertThat(service.inbox(workspace).getFirst().event().text()).isEqualTo("Correction");
        jdbc.update("DELETE FROM workspace WHERE id=?", workspace);
        assertThat(store.<Integer>read(data -> data.items().size())).isZero();
        assertThat(service.routes()).extracting(SlackInboxService.Route::workspaceId).containsExactly(other);
        listener.get().event(original);
        assertThat(service.inbox(other)).isEmpty();
    }

    @Test void workspaceDeletionCascadesRoutesMessagesAndConversationLinks() {
        listener.get().event(root("EvROOT", "1800000000.1"));
        service.send(workspace, service.inbox(workspace).getFirst().id(), "Synthetic inquiry");
        jdbc.update("DELETE FROM workspace WHERE id=?", workspace);
        var restarted = restart();
        assertThat(restarted.routes()).isEmpty();
        assertThat(restarted.status().inboxCount()).isZero();
        assertThat(store.<Integer>read(data -> data.threads().size())).isZero();
        assertThatThrownBy(() -> restarted.inbox(workspace)).hasMessageContaining("Workspace");
        restarted.close();
    }
}
