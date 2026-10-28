package dev.dougle;

import dev.dougle.desktop.DesktopSession;
import dev.dougle.library.adapter.web.ApiErrors;
import dev.dougle.library.adapter.web.LocalRequestFilter;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.sources.application.SourceStore;
import dev.dougle.transfer.adapter.TeamSettingsController;
import dev.dougle.transfer.application.TeamSettingsService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamSettingsControllerTest {
    private final String token = "a".repeat(64);
    private final LibraryStore library = mock(LibraryStore.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TeamSettingsController(new TeamSettingsService(library, sources)))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new LocalRequestFilter(DesktopSession.read(new ByteArrayInputStream((token + "\n").getBytes(StandardCharsets.US_ASCII)))))
            .build();
    private final String valid = "{\"version\":1,\"workspaceName\":\"Team\",\"pollIntervalSeconds\":3600,\"sources\":[]}";

    @Test
    void requiresSessionSameOriginAndClientHeader() throws Exception {
        mvc.perform(get("/api/team-settings/export/id")).andExpect(status().isForbidden());
        mvc.perform(post("/api/team-settings/import-preview").header("X-Forge-Session", token)
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        mvc.perform(post("/api/team-settings/import-preview").header("X-Forge-Session", token).header("X-Forge-Client", "1")
                .header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
        verifyNoInteractions(library, sources);
    }

    @Test
    void authenticatedPreviewIsReadOnlyAndApplyDoesNotExist() throws Exception {
        when(library.workspaces()).thenReturn(List.of());
        mvc.perform(post("/api/team-settings/import-preview").header("X-Forge-Session", token).header("X-Forge-Client", "1")
                .header("Origin", "http://localhost:80").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applySupported").value(true));
        mvc.perform(post("/api/team-settings/import").header("X-Forge-Session", token).header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isNotFound());
        verify(library).workspaces();
        verifyNoMoreInteractions(library);
        verifyNoInteractions(sources);
    }

    @Test
    void rejectsUnknownDuplicateMissingMistypedTrailingAndOversizedInput() throws Exception {
        for (String input : List.of(valid.replace("\"version\":1", "\"version\":1,\"credentials\":\"secret\""),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"), valid.replace("\"version\":1,", ""),
                valid.replace("\"version\":1", "\"version\":1.0"), valid + "{}", "null", "[]", "{")) {
            mvc.perform(post("/api/team-settings/import-preview").header("X-Forge-Session", token).header("X-Forge-Client", "1")
                    .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/team-settings/import-preview").header("X-Forge-Session", token).header("X-Forge-Client", "1")
                .contentType(MediaType.APPLICATION_JSON).content(" ".repeat(TeamSettingsController.MAX_BYTES + 1)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(library, sources);
    }
}
