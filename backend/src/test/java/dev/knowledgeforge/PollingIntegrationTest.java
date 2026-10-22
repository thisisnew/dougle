package dev.knowledgeforge;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.sources.application.SourceService;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.sources.domain.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "forge.polling.initial-delay-ms=3600000")
@AutoConfigureMockMvc
class PollingIntegrationTest {
    @TempDir static Path data;
    @TempDir Path fixture;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", () -> data.toString());
    }
    @Autowired SourceService sources;
    @Autowired SourceStore store;
    @Autowired LibraryService library;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    private Source source;
    private long now;

    private void time(long millis) {
        now = millis;
        when(clock.millis()).thenReturn(millis);
        when(clock.instant()).thenReturn(Instant.ofEpochMilli(millis));
    }

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM workspace");
        time(1_800_000_000_000L);
        Files.writeString(fixture.resolve("synthetic.txt"), "synthetic research");
        String workspace = library.createWorkspace("Polling fixture").id();
        source = sources.create(workspace, "Synthetic", "LOCAL_FOLDER", fixture.toString(), "");
    }

    private Source settings(boolean enabled, int interval, int revision) {
        return sources.configurePolling(source.workspaceId(), source.id(), enabled, interval, revision);
    }

    private Source current() { return store.find(source.workspaceId(), source.id()).orElseThrow(); }

    @Test
    void defaultsOffAndEnablingWaitsAnIntervalBeforeCollecting() {
        sources.poll(source.workspaceId(), source.id());
        assertThat(current().status()).isEqualTo("NEW");
        assertThat(store.due(2)).isEmpty();
        Source configured = settings(true, 60, 0);
        assertThat(configured.nextPollAt()).isEqualTo(now + 60_000);
        assertThat(configured.pollingRevision()).isEqualTo(1);
        time(now + 59_999);
        assertThat(store.due(2)).isEmpty();
        time(now + 1);
        assertThat(store.due(2)).hasSize(1);
        sources.poll(source.workspaceId(), source.id());
        assertThat(current().status()).isEqualTo("READY");
        assertThat(current().nextPollAt()).isEqualTo(now + 60_000);
        assertThat(current().consecutiveFailures()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry WHERE kind='FILE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void manualAndAutomaticClaimsAreMutuallyExclusive() {
        settings(true, 60, 0);
        time(now + 60_000);
        assertThat(store.begin(source.workspaceId(), source.id())).isTrue();
        assertThat(store.beginPoll(source.workspaceId(), source.id())).isFalse();
        store.fail(source.id(), "synthetic failure");
        time(current().nextPollAt());
        assertThat(store.beginPoll(source.workspaceId(), source.id())).isTrue();
        assertThat(store.begin(source.workspaceId(), source.id())).isFalse();
    }

    @Test
    void failureBackoffIsBoundedAndSuccessResetsIt() {
        settings(true, 60, 0);
        store.fail(source.id(), "synthetic failure");
        assertThat(current().nextPollAt()).isEqualTo(now + 120_000);
        for (int attempt = 0; attempt < 30; attempt++) store.fail(source.id(), "synthetic failure");
        assertThat(current().nextPollAt()).isEqualTo(now + 86_400_000);
        assertThat(current().consecutiveFailures()).isEqualTo(16);
        sources.sync(source.workspaceId(), source.id());
        assertThat(current().consecutiveFailures()).isZero();
        assertThat(current().nextPollAt()).isEqualTo(now + 60_000);
    }

    @Test
    void disablingAfterClaimDoesNotRescheduleOnCompletion() {
        settings(true, 60, 0);
        time(now + 60_000);
        assertThat(store.beginPoll(source.workspaceId(), source.id())).isTrue();
        settings(false, 60, 1);
        store.fail(source.id(), "synthetic stopped collection");
        assertThat(current().nextPollAt()).isNull();
        assertThat(store.due(2)).isEmpty();
        assertThat(store.beginPoll(source.workspaceId(), source.id())).isFalse();
    }

    @Test
    void deletedAndOtherWorkspaceSourcesCannotBeClaimedOrConfigured() {
        String other = library.createWorkspace("Other").id();
        assertThatThrownBy(() -> sources.configurePolling(other, source.id(), true, 60, 0)).isInstanceOf(LibraryException.class);
        assertThat(store.beginPoll(other, source.id())).isFalse();
        settings(true, 60, 0);
        sources.delete(source.workspaceId(), source.id());
        time(now + 60_000);
        sources.poll(source.workspaceId(), source.id());
        assertThat(store.due(2)).isEmpty();
    }

    @Test
    void apiValidatesSettingsAndRejectsStaleRevisionWithoutCollecting() throws Exception {
        String route = "/api/workspaces/" + source.workspaceId() + "/sources/" + source.id() + "/polling";
        mvc.perform(patch(route).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"enabled\":true,\"intervalSeconds\":59,\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(route).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"enabled\":true,\"intervalSeconds\":60,\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pollingEnabled").value(true))
                .andExpect(jsonPath("$.status").value("NEW"));
        mvc.perform(patch(route).header("X-Forge-Client", "1").contentType("application/json")
                .content("{\"enabled\":false,\"intervalSeconds\":60,\"expectedRevision\":0}"))
                .andExpect(status().isConflict());
        assertThat(current().pollingEnabled()).isTrue();
    }
}
