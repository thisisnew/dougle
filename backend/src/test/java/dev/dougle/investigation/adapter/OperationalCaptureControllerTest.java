package dev.dougle.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.dougle.library.application.LibraryException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OperationalCaptureControllerTest {
    private final OperationalCaptureService service = mock(OperationalCaptureService.class);
    private final OperationalCaptureController controller = new OperationalCaptureController(service);

    @Test
    void rejectsClientOutputSqlCredentialsNumericEnumsAndOversizedBodies() {
        for (String body : List.of("null", "{}", "{\"provider\":0,\"resultId\":\"result\"}",
                "{\"provider\":\"MYSQL\",\"resultId\":\"result\",\"output\":\"forged\"}",
                "{\"provider\":\"MYSQL\",\"resultId\":\"result\",\"sql\":\"SELECT *\"}",
                "{\"provider\":\"MYSQL\",\"resultId\":\"result\",\"password\":\"private\"}",
                "{\"provider\":\"MYSQL\",\"provider\":\"DATADOG\",\"resultId\":\"result\"}",
                "{\"provider\":\"MYSQL\",\"resultId\":\"result\"}{}", "x".repeat(2049))) {
            var failure = assertThrows(LibraryException.class, () -> controller.preview("workspace", request(body)));
            assertFalse(failure.getMessage().contains("private"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsCoercedOrMissingConsentAndUnknownCaptureFields() {
        for (String body : List.of("{}", "{\"requestId\":\"synthetic-request-1234\",\"digest\":\"digest\"}",
                "{\"requestId\":\"synthetic-request-1234\",\"digest\":\"digest\",\"approved\":\"true\"}",
                "{\"requestId\":\"synthetic-request-1234\",\"digest\":\"digest\",\"approved\":true,\"text\":\"forged\"}")) {
            assertThrows(LibraryException.class, () -> controller.capture("workspace", "preview", request(body)));
        }
        verifyNoInteractions(service);
    }

    @Test
    void exactDtosAndGetAreForwardedWithoutExecutingAnyTool() throws Exception {
        controller.preview("workspace", request("{\"provider\":\"DATADOG\",\"resultId\":\"result\"}"));
        verify(service).preview("workspace", new OperationalCaptureService.Input(OperationalCaptureService.Provider.DATADOG, "result"));
        controller.capture("workspace", "preview", request("{\"requestId\":\"synthetic-request-1234\",\"digest\":\"digest\",\"approved\":true}"));
        verify(service).capture("workspace", "preview", new OperationalCaptureService.Start("synthetic-request-1234", "digest", true));
        controller.get("workspace", "preview");
        verify(service).get("workspace", "preview");
        verifyNoMoreInteractions(service);
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
