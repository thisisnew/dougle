package dev.dougle;

import com.zaxxer.hikari.HikariDataSource;
import dev.dougle.ingestion.application.AnalysisStore;
import dev.dougle.library.application.LibraryService;
import dev.dougle.sources.application.SourceService;
import dev.dougle.sources.application.SourceSyncJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SourceSyncContentionTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-source-contention-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
        registry.add("forge.polling.initial-delay-ms", () -> "600000");
    }
    @Autowired LibraryService library;
    @Autowired SourceService sources;
    @Autowired SourceSyncJobs jobs;
    @Autowired HikariDataSource pool;
    @Autowired MockMvc mvc;
    @MockitoSpyBean AnalysisStore analyses;

    @Test @org.junit.jupiter.api.Timeout(90) void statusAndProgressRemainAvailableWhilePublishOwnsTheOnlySqliteConnection() throws Exception {
        String workspace = library.createWorkspace("slow publish fixture").id();
        Path folder = Files.createDirectory(DATA.resolve("source"));
        Files.writeString(folder.resolve("FeatureFlagClient.java"), "class FeatureFlagClient { String provider = \"flipt\"; }");
        var source = sources.create(workspace, "source", "LOCAL_FOLDER", folder.toString(), "");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("fixture release timeout");
            return call.callRealMethod();
        }).when(analyses).save(anyString(), any());
        var job = jobs.start(workspace, source.id());
        String base = "/api/workspaces/" + workspace + "/sources/" + source.id();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            assertThatThrownBy(() -> sources.list(workspace))
                    .isInstanceOf(org.springframework.jdbc.CannotGetJdbcConnectionException.class);
            // Real HTTP controller calls while the transaction remains open beyond pool acquisition timeout.
            for (int attempt = 0; attempt < 3; attempt++) {
                mvc.perform(get(base + "/sync-jobs/" + job.id())).andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("RUNNING"));
                mvc.perform(get(base + "/progress")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.stage").value("PUBLISHING"))
                        .andExpect(jsonPath("$.cancellable").value(false));
                Thread.sleep(150);
            }
            mvc.perform(get(base.replace(workspace, "other") + "/sync-jobs/" + job.id())).andExpect(status().isNotFound());
            mvc.perform(get(base.replace(workspace, "other") + "/progress")).andExpect(status().isNotFound());
            mvc.perform(post(base.replace(workspace, "other") + "/cancel").header("X-Forge-Client", "1")).andExpect(status().isNotFound());
        } finally { release.countDown(); }
        for (int attempt = 0; attempt < 500 && jobs.get(workspace, source.id(), job.id()).state().equals("RUNNING"); attempt++) Thread.sleep(20);
        var completed = jobs.get(workspace, source.id(), job.id());
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.source().status()).isEqualTo("READY");
        assertThat(Files.readString(folder.resolve("FeatureFlagClient.java"))).contains("flipt");
    }
}
