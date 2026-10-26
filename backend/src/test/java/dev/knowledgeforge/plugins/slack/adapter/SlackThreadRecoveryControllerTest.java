package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackInboxService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackThreadRecoveryControllerTest {
    @Test void exposesStructuredMissingThreadConflictAndPassesExplicitRecovery() {
        var service = mock(SlackInboxService.class);
        var controller = new SlackController(service);
        var problem = controller.missingThread(new SlackInboxService.MissingThreadException("old-thread"));
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "SLACK_THREAD_MISSING")
                .containsEntry("expectedThreadId", "old-thread");
        controller.send("workspace", "item", "{\"question\":\"Synthetic\"}");
        controller.send("workspace", "item", "{\"question\":\"Synthetic\",\"startNewConversation\":true,\"expectedThreadId\":\"old-thread\"}");
        verify(service).send("workspace", "item", "Synthetic", false, null);
        verify(service).send("workspace", "item", "Synthetic", true, "old-thread");
        verifyNoMoreInteractions(service);
    }

    @Test void rejectsCoercedMissingDuplicateUnknownAndTrailingRecoveryFields() {
        for (String body : new String[] {
                "{\"question\":\"Synthetic\",\"startNewConversation\":true}",
                "{\"question\":\"Synthetic\",\"startNewConversation\":\"true\",\"expectedThreadId\":\"old-thread\"}",
                "{\"question\":\"Synthetic\",\"startNewConversation\":false,\"expectedThreadId\":\"old-thread\"}",
                "{\"question\":\"Synthetic\",\"startNewConversation\":true,\"expectedThreadId\":\"../other\"}",
                "{\"question\":\"Synthetic\",\"question\":\"Other\"}",
                "{\"question\":\"Synthetic\",\"extra\":true}", "{\"question\":\"Synthetic\"}{}", " ".repeat(4097) }) {
            assertThatThrownBy(() -> SlackController.sendRequest(body)).hasMessageContaining("JSON").hasCause(null);
        }
    }
}
