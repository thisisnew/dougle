package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.plugins.slack.application.SlackReplyAccess;
import dev.knowledgeforge.plugins.slack.application.SlackReplyService;
import dev.knowledgeforge.plugins.slack.application.SlackReplyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackReplyTransportTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "Synthetic", "<!channel>", "xoxb-synthetic-secret" })
    void directTransportRejectsBeforeReadingLeaseCredentialsOrCheckingScope(String body) {
        var lease = mock(SlackReplyAccess.Lease.class);
        var transport = new SlackReplyHttpTransport();
        assertThatThrownBy(() -> transport.post(lease, body))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:").hasCause(null);
        verifyNoInteractions(lease);
    }

    @Test void absentLeaseCannotBypassReadOnlyPolicy() {
        assertThatThrownBy(() -> new SlackReplyHttpTransport().post(null, null))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:");
    }

    @Test void directServiceDeniesBeforeAnyPersistenceLookupOrClaim() {
        var store = mock(SlackReplyStore.class);
        var library = mock(LibraryStore.class);
        var service = new SlackReplyService(store, library);
        assertThatThrownBy(() -> service.preview(null, null)).hasMessageStartingWith("READ_ONLY:");
        assertThatThrownBy(() -> service.execute(null, null, null)).hasMessageStartingWith("READ_ONLY:");
        assertThatThrownBy(() -> service.execute("workspace", "old-preview",
                new SlackReplyService.ExecuteInput("approved-request", "valid-digest", true)))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:");
        assertThatThrownBy(() -> service.acknowledge(null, null)).hasMessageStartingWith("READ_ONLY:");
        verifyNoInteractions(store, library);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "{}", "{}{}", "{\"approved\":true}", "{\"approved\":true,\"approved\":false}", "secret-body" })
    void controllerDeniesLegacyPostingRoutesBeforeParsingOrCallingService(String body) {
        var service = mock(SlackReplyService.class);
        var controller = new SlackReplyController(service);
        assertThatThrownBy(() -> controller.preview("workspace", body))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:").hasCause(null);
        assertThatThrownBy(() -> controller.execute("workspace", "old-preview", body))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(403))
                .hasMessageStartingWith("READ_ONLY:").hasCause(null);
        assertThatThrownBy(() -> controller.acknowledge("workspace", "old-preview", body))
                .hasMessageStartingWith("READ_ONLY:");
        verifyNoInteractions(service);
    }

    @Test void controllerPreservesReadAndStrictLocalCancelRoutes() {
        var service = mock(SlackReplyService.class);
        var controller = new SlackReplyController(service);
        controller.get("workspace", "reply");
        controller.list("workspace", "item");
        controller.cancel("workspace", "reply", "{}");
        verify(service).get("workspace", "reply");
        verify(service).list("workspace", "item");
        verify(service).cancel("workspace", "reply");
        for (String body : new String[] { "{}{}", "{\"approved\":true}", " ".repeat(32769) }) {
            assertThatThrownBy(() -> controller.cancel("workspace", "reply", body)).hasMessageContaining("JSON");
        }
        verifyNoMoreInteractions(service);
    }
}
