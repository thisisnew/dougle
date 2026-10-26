package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlInvestigationControllerTest {
    final MysqlInvestigationService service = mock(MysqlInvestigationService.class);
    final MysqlInvestigationController controller = new MysqlInvestigationController(service);

    MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test void rejectsCoercedConsentMissingDuplicateAndUnknownFields() {
        for (String body : java.util.List.of("null", "{}", "{\"requestId\":\"fixture-request-id\",\"approved\":\"true\"}",
                "{\"requestId\":\"fixture-request-id\",\"approved\":true,\"approved\":false}",
                "{\"requestId\":\"fixture-request-id\",\"approved\":true,\"sql\":\"DELETE\"}",
                "{\"requestId\":\"fixture-request-id\",\"approved\":true}{}")) {
            assertThatThrownBy(() -> controller.execute("workspace", "preview", request(body))).isInstanceOf(LibraryException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void rejectsOversizedSqlAndFractionalRowLimits() {
        for (String body : java.util.List.of(" ".repeat(8193), "{\"sql\":\"SELECT * FROM receipts\"}",
                "{\"table\":\"receipts\",\"columns\":[\"id\"],\"equalities\":{\"id\":\"1\"},\"limit\":1.5}")) {
            assertThatThrownBy(() -> controller.preview("workspace", request(body))).isInstanceOf(LibraryException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void exactConsentIsForwardedWithoutChangingScope() throws Exception {
        controller.execute("workspace", "preview", request("{\"requestId\":\"fixture-request-id\",\"approved\":true}"));
        verify(service).execute("workspace", "preview", "fixture-request-id", true);
    }

    @Test void cancellationReturnsNoContentRatherThanAnEmptyJsonResponse() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/workspaces/workspace/mysql-investigations/preview/cancel"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        verify(service).cancel("workspace", "preview");
    }
}
