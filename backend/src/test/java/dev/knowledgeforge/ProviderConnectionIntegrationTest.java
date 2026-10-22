package dev.knowledgeforge;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.ProviderConnection;
import dev.knowledgeforge.knowledge.application.ProviderLogin;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProviderConnectionIntegrationTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", data::toString); }
    @Autowired MockMvc mvc;
    @MockitoBean ProviderConnection connection;
    @MockitoBean ProviderLogin login;

    @Test
    void connectionRequiresExplicitMutationAndKnownProvider() throws Exception {
        mvc.perform(post("/api/ai/providers/CODEX/connection")).andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/providers/UNKNOWN/connection").header("X-Forge-Client", "1")).andExpect(status().isBadRequest());
        verifyNoInteractions(connection);
        when(connection.check(Provider.CODEX)).thenReturn(new ProviderConnection.Status("CODEX", "AUTHENTICATED", "time", "NOT_CHECKED"));
        mvc.perform(post("/api/ai/providers/CODEX/connection").header("X-Forge-Client", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.remoteConnection").value("NOT_CHECKED"))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        verify(connection).check(Provider.CODEX);
    }

    @Test
    void loginEndpointsPreserveMutationGuardAndExplicitConsent() throws Exception {
        mvc.perform(post("/api/ai/logins").contentType("application/json").content("{\"provider\":\"CODEX\",\"acknowledged\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/ai/logins/request")).andExpect(status().isForbidden());
        verifyNoInteractions(login);
        when(login.start(Provider.CODEX, false)).thenThrow(new dev.knowledgeforge.library.application.LibraryException(400, "Consent required"));
        mvc.perform(post("/api/ai/logins").header("X-Forge-Client", "1").contentType("application/json").content("{\"provider\":\"CODEX\"}"))
                .andExpect(status().isBadRequest());
        when(login.start(Provider.CODEX, true)).thenReturn(new ProviderLogin.Status("request", "CODEX", "PENDING"));
        mvc.perform(post("/api/ai/logins").header("X-Forge-Client", "1").contentType("application/json").content("{\"provider\":\"CODEX\",\"acknowledged\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
    }
}
