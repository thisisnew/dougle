package dev.dougle;

import dev.dougle.knowledge.application.CliLaunchConfiguration;
import dev.dougle.knowledge.application.CliLaunchStore;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import dev.dougle.library.application.LibraryException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CliLaunchIntegrationTest {
    @TempDir static Path data;
    @TempDir Path fixture;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", () -> data.toString()); }
    @Autowired CliLaunchConfiguration settings;
    @Autowired CliLaunchStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    Path executable;
    Path marker;

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("UPDATE cli_launch_settings SET codex_path='',claude_path='',revision=0");
        marker = fixture.resolve("must-not-execute");
        executable = fixture.resolve("synthetic-cli");
        Files.writeString(executable, "#!/bin/sh\ntouch '" + marker + "'\n");
        executable.toFile().setExecutable(true, true);
    }

    @Test
    void explicitSaveIsDeferredUntilRestartAndChangesExecutionBinding() {
        String before = settings.binding(Provider.CODEX);
        var saved = settings.save(executable.toString(), "", 0, true);
        assertThat(saved.restartRequired()).isTrue();
        assertThat(saved.saved().revision()).isEqualTo(1);
        assertThat(settings.binding(Provider.CODEX)).isEqualTo(before);
        assertThat(Files.exists(marker)).isFalse();
        var restarted = new CliLaunchConfiguration(store, "codex", "claude");
        assertThat(restarted.command(Provider.CODEX)).isEqualTo(executable.toString());
        assertThat(restarted.view().restartRequired()).isFalse();
        assertThat(restarted.binding(Provider.CODEX)).isNotEqualTo(before);
        assertThat(restarted.binding(Provider.CLAUDE)).isEqualTo(settings.binding(Provider.CLAUDE));
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void rejectsUnacknowledgedAndStaleSettingsAndCanRestoreDefault() {
        assertThatThrownBy(() -> settings.save(executable.toString(), "", 0, false)).isInstanceOf(LibraryException.class);
        settings.save(executable.toString(), "", 0, true);
        assertThatThrownBy(() -> settings.save("", "", 0, true)).isInstanceOf(LibraryException.class);
        assertThat(store.current().codexPath()).isEqualTo(executable.toString());
        assertThat(settings.save("", "", 1, true).restartRequired()).isFalse();
    }

    @Test
    void rejectsRelativeMissingNonExecutableDirectoriesAndControlCharacters() throws Exception {
        Path text = fixture.resolve("not-executable");
        Files.writeString(text, "synthetic");
        for (String invalid : java.util.List.of("dougle-synthetic-command-that-does-not-exist", "../codex", fixture.toString(), text.toString(),
                fixture.resolve("missing").toString(), executable + " --version", executable + "\nextra")) {
            assertThatThrownBy(() -> settings.save(invalid, "", 0, true)).as(invalid).isInstanceOf(LibraryException.class);
        }
        assertThat(store.current().revision()).isZero();
    }

    @Test
    void apiRequiresLocalMutationHeaderAndReturnsPendingRestartWithoutExecution() throws Exception {
        String payload = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
                "codexPath", executable.toString(), "claudePath", "", "expectedRevision", 0, "acknowledged", true));
        mvc.perform(put("/api/ai/launch-settings").contentType("application/json").content(payload)).andExpect(status().isForbidden());
        mvc.perform(put("/api/ai/launch-settings").header("X-Forge-Client", "1").contentType("application/json").content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.restartRequired").value(true));
        mvc.perform(get("/api/ai/launch-settings")).andExpect(status().isOk()).andExpect(jsonPath("$.saved.revision").value(1));
        assertThat(Files.exists(marker)).isFalse();
    }
}
