package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.knowledgeforge.library.application.LibraryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ConversationOperationalReadControllerTest {
    private final ConversationOperationalReadService service = mock(ConversationOperationalReadService.class);
    private final ConversationOperationalReadController controller = new ConversationOperationalReadController(service);

    @Test
    void previewRejectsDirectQueriesCredentialsCoercionAndUnknownFields() {
        for (String body : List.of("null", "{}", "{\"scopeId\":\"scope\",\"scopeRevision\":1,\"query\":{}}",
                "{\"scopeId\":\"scope\",\"scopeRevision\":1,\"sql\":\"SELECT *\"}",
                "{\"scopeId\":\"scope\",\"scopeRevision\":1,\"password\":\"private\"}",
                "{\"scopeId\":\"scope\",\"scopeRevision\":\"1\"}", "{\"scopeId\":\"scope\",\"scopeRevision\":1.5}",
                "{\"scopeId\":\"scope\",\"scopeRevision\":1,\"scopeRevision\":2}",
                "{\"scopeId\":\"scope\",\"scopeRevision\":1}{}", "x".repeat(2049))) {
            var failure = assertThrows(LibraryException.class, () -> controller.preview("workspace", request(body)));
            assertFalse(failure.getMessage().contains("private"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void executionAndCaptureRejectCoercedMissingOrAugmentedConsent() {
        for (String body : List.of("{}", "{\"requestId\":\"request\",\"digest\":\"digest\"}",
                "{\"requestId\":\"request\",\"digest\":\"digest\",\"approved\":\"true\"}",
                "{\"requestId\":\"request\",\"digest\":\"digest\",\"approved\":true,\"output\":\"forged\"}")) {
            assertThrows(LibraryException.class, () -> controller.execute("workspace", "read", request(body)));
            assertThrows(LibraryException.class, () -> controller.capture("workspace", "read", request(body)));
        }
        verifyNoInteractions(service);
    }

    @Test
    void exactDtosAndReadOnlyRecoveryForwardToBridge() throws Exception {
        controller.preview("workspace", request("{\"scopeId\":\"scope\",\"scopeRevision\":1}"));
        verify(service).preview("workspace", new ConversationOperationalReadService.Input("scope", 1));
        var consent = "{\"requestId\":\"request\",\"digest\":\"digest\",\"approved\":true}";
        var expected = new ConversationOperationalReadService.Start("request", "digest", true);
        controller.execute("workspace", "read", request(consent));
        controller.get("workspace", "read");
        controller.cancel("workspace", "read");
        controller.capturePreview("workspace", "read");
        controller.capture("workspace", "read", request(consent));
        controller.captureReceipt("workspace", "read");
        verify(service).execute("workspace", "read", expected);
        verify(service).get("workspace", "read");
        verify(service).cancel("workspace", "read");
        verify(service).capturePreview("workspace", "read");
        verify(service).capture("workspace", "read", expected);
        verify(service).captureReceipt("workspace", "read");
        verifyNoMoreInteractions(service);
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
