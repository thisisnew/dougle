package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.slack.application.*;
import dev.knowledgeforge.plugins.slack.domain.SlackReply;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class SlackReplyIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-slack-readonly-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired SlackReplyService replies;
    @Autowired SlackReplyStore store;
    @Autowired LibraryService library;
    @Autowired LibraryStore libraryStore;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SlackProvider provider;
    @MockitoBean SlackReplyTransport transport;
    String workspace;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM slack_reply");
        jdbc.update("DELETE FROM workspace");
        workspace = library.createWorkspace("Read only fixture").id();
    }

    SlackReply history(String status) {
        var scope = new SlackReplyAccess.Scope(workspace, "item-one", "source-digest", "TTEAM", "CCHANNEL",
                "1800000000.1", "UBOT", "historical-session");
        boolean dispatched = java.util.Set.of("SENDING", "SENT", "FAILED", "UNCERTAIN").contains(status);
        var value = new SlackReply("reply-one", workspace, "item-one", "old-preview", "a".repeat(64),
                new JsonMapper().writeValueAsString(scope), "Historical synthetic reply", "b".repeat(64), status,
                dispatched ? "old-execute" : null, dispatched ? 1 : 0,
                status.equals("SENT") ? "1800000001.1" : null, status.equals("UNCERTAIN") ? "RESTART" : null,
                "2026-10-25T00:00:00Z", "2099-01-01T00:00:00Z", "2026-10-25T00:00:00Z");
        store.insert(value);
        return value;
    }

    @Test void previewIsPermanentlyDeniedAndCreatesNoReceipt() {
        assertThatThrownBy(() -> replies.preview(workspace,
                new SlackReplyService.PreviewInput("new-request", "item-one", "Synthetic")))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:");
        assertThat(store.count()).isZero();
        verifyNoInteractions(provider, transport);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PREVIEW", "SENDING", "SENT", "FAILED", "UNCERTAIN", "CANCELLED", "EXPIRED" })
    void oldValidApprovalAndReplayAreDeniedWithoutChangingHistoricalReceipt(String status) {
        var old = history(status);
        assertThatThrownBy(() -> replies.execute(workspace, old.id(),
                new SlackReplyService.ExecuteInput("old-execute", old.digest(), true)))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:");
        assertThatThrownBy(() -> replies.preview(workspace,
                new SlackReplyService.PreviewInput(old.previewRequestId(), old.inboxItemId(), old.body())))
                .hasMessageStartingWith("READ_ONLY:");
        assertThat(store.find(workspace, old.id())).contains(old);
        var view = replies.get(workspace, old.id());
        assertThat(view.status()).isEqualTo(status);
        assertThat(view.body()).isEqualTo(old.body());
        assertThat(view.dispatchCount()).isEqualTo(old.dispatchCount());
        assertThat(replies.list(workspace, old.inboxItemId())).containsExactly(view);
        var restored = new SlackReplyService(new SlackReplyJdbcStore(jdbc, transactions), libraryStore);
        assertThat(restored.get(workspace, old.id())).isEqualTo(view);
        assertThatThrownBy(() -> restored.execute(workspace, old.id(),
                new SlackReplyService.ExecuteInput("old-execute", old.digest(), true)))
                .hasMessageStartingWith("READ_ONLY:");
        verifyNoInteractions(provider, transport);
    }

    @Test void localCancellationStillWorksWithoutCredentialsOrOutboundCalls() {
        var old = history("PREVIEW");
        assertThat(replies.cancel(workspace, old.id()).status()).isEqualTo("CANCELLED");
        assertThat(replies.cancel(workspace, old.id()).dispatchCount()).isZero();
        assertThat(replies.get(workspace, old.id()).body()).isEqualTo(old.body());
        verifyNoInteractions(provider, transport);
    }

    @ParameterizedTest
    @ValueSource(strings = { "SENDING", "SENT", "FAILED", "UNCERTAIN", "CANCELLED", "EXPIRED" })
    void cancellationNeverRewritesHistoricalDeliveryOutcome(String status) {
        var old = history(status);
        assertThat(replies.cancel(workspace, old.id()).status()).isEqualTo(status);
        assertThat(store.find(workspace, old.id())).contains(old);
        verifyNoInteractions(provider, transport);
    }

    @Test void uncertaintyAcknowledgementCannotEnablePostingOrRewriteHistory() {
        var old = history("UNCERTAIN");
        assertThatThrownBy(() -> replies.acknowledge(workspace, old.id())).hasMessageStartingWith("READ_ONLY:");
        assertThat(store.find(workspace, old.id())).contains(old);
        verifyNoInteractions(provider, transport);
    }

    @Test void historyReadAndCancellationRemainWorkspaceScoped() {
        var old = history("PREVIEW");
        String other = library.createWorkspace("Other fixture").id();
        assertThat(replies.list(other, old.inboxItemId())).isEmpty();
        assertThatThrownBy(() -> replies.get(other, old.id()))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        assertThatThrownBy(() -> replies.cancel(other, old.id()))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        assertThat(store.find(workspace, old.id())).contains(old);
        verifyNoInteractions(provider, transport);
    }
}
