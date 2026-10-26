package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class ProviderProcessIoTest {
    @TempDir Path directory;

    @Test
    void streamAndExecProgressWithOneVirtualCarrier() throws Exception {
        Path log = directory.resolve("synthetic-process-io.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.virtualThreadScheduler.parallelism=1", "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp", System.getProperty("java.class.path"), getClass().getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var descendants = new HashSet<ProcessHandle>();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                process.descendants().forEach(descendants::add);
                process.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertThat(process.isAlive()).as("Bounded synthetic process probe must finish").isFalse();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            assertThat(descendants).allMatch(child -> !child.isAlive());
        } finally {
            process.descendants().forEach(descendants::add);
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        var stream = new CodexAppServerTest();
        stream.directory = Files.createDirectory(root.resolve("stream"));
        stream.optInRoutesCodexAnswerThroughIsolatedStreamAndReleasesProcessAndLock();
        for (Provider provider : Provider.values()) {
            var planning = new CliPlanTransportTest();
            planning.directory = Files.createDirectory(root.resolve("plan-" + provider.name()));
            planning.explicitPlanRouteUsesFixedSchemaAndSharedRestrictions(provider);
        }
    }
}
