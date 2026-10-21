package dev.knowledgeforge;

import dev.knowledgeforge.knowledge.application.CuratorService;
import dev.knowledgeforge.knowledge.application.KnowledgeService;
import dev.knowledgeforge.knowledge.domain.Knowledge.Citation;
import dev.knowledgeforge.library.application.LibraryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "FORGE_LIVE_CLI", matches = "CODEX|CLAUDE")
class CliCuratorLiveTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-live-cli-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired CuratorService curator;
    @Autowired KnowledgeService knowledge;
    @Autowired LibraryService library;

    @Test
    void generatesRealUnverifiedDraftFromSyntheticEvidenceOnly() {
        String provider = System.getenv("FORGE_LIVE_CLI");
        String workspace = library.createWorkspace("Synthetic CLI check").id();
        try {
            String entry = library.upload(workspace, null, "synthetic.txt", "가상 관측 기록: 실험실 센서의 측정 온도는 21도이다.".getBytes(StandardCharsets.UTF_8)).id();
            var source = knowledge.create(workspace, "가상 초안", "사람 메모", List.of(new Citation(entry, 1, 1)));
            var preview = curator.preview(workspace, source.card().id(), provider);
            long started = System.nanoTime();
            var result = curator.generate(workspace, source.card().id(), provider, preview.expectedVersion(), preview.digest(), true);
            assertThat(result.card().id()).isNotEqualTo(source.card().id());
            assertThat(result.version().manuallyEdited()).isFalse();
            assertThat(result.version().status()).isEqualTo("DRAFT");
            assertThat(result.version().markdown()).contains("[E1]");
            assertThat(knowledge.get(workspace, source.card().id(), null)).isEqualTo(source);
            System.out.printf("LIVE_CLI provider=%s duration_ms=%d chars=%d%n", provider,
                    (System.nanoTime() - started) / 1_000_000, result.version().markdown().length());
        } finally {
            library.deleteWorkspace(workspace);
        }
    }
}
