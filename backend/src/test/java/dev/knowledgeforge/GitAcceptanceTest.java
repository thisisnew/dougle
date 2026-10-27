package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.adapter.GitRunner;
import dev.knowledgeforge.sources.adapter.GithubCollector;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.git", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitAcceptanceTest {
    record Fixture(Path root, String original, String next, List<String> manifest, String setupFailure) {}
    @TempDir static Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();

    @BeforeAll void prepare() throws Exception {
        for (int number = 0; number < 20; number++) {
            Path root = Files.createDirectories(directory.resolve("repository-" + number));
            try {
                git(root, "init", "--quiet", "--initial-branch=" + (number == 4 ? "trunk" : "main"));
                String original;
                if (number < 2) {
                    String repository = number == 0 ? "study-gof-23-design-pattern" : "design-pattern";
                    String revision = number == 0 ? "fd98bd00d958e23f8b3f05c379b2747da92e8d68" : "b675f8f1342f1bddaa96c613875841a407306bcd";
                    git(root, "fetch", "--quiet", "--depth=1", "--", "https://github.com/thisisnew/" + repository + ".git", revision);
                    git(root, "update-ref", "HEAD", revision);
                    git(root, "read-tree", revision);
                    original = revision;
                } else {
                    if (number != 3) {
                        write(root, "src/Strategy.java", "package sample; public interface Strategy { int choose(); }");
                        write(root, "src/WinningStrategy.java", "package sample; public class WinningStrategy implements Strategy { public int choose() { return 72; } }");
                        write(root, "src/Player.java", "package sample; public class Player { Strategy strategy; int play() { return strategy.choose(); } }");
                    }
                    if (number == 8) {
                        write(root, "state/Main.java", "class Main { Object state = NightState.getInstance(); }");
                        write(root, "strategy/Main.java", "class Main { Strategy strategy = new WinningStrategy(); }");
                    }
                    if (number == 10) write(root, "large.java", "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 123456789\n");
                    if (number == 12) {
                        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        javax.imageio.ImageIO.write(image, "png", root.resolve("image.png").toFile());
                    }
                    if (number == 13) write(root, "Generated.java", "/*" + "generated filler\n".repeat(800_000) + "*/ class Generated {}\n");
                    if (number == 14) Files.write(root.resolve("Invalid.java"), new byte[]{'/', '/', ' ', (byte) 0xc3, (byte) 0x28, '\n', 'c', 'l', 'a', 's', 's', ' ', 'X', '{', '}'});
                    git(root, "add", "--", ".");
                    original = commit(root);
                    if (number == 11) {
                        git(root, "update-index", "--add", "--cacheinfo", "160000," + original + ",external-module");
                        original = commit(root);
                    }
                }
                List<String> manifest = new String(git(root, "ls-tree", "-r", "--name-only", original), StandardCharsets.UTF_8)
                        .lines().filter(path -> !path.isBlank()).filter(path -> {
                            var exclusions = Set.of(".git", ".ssh", ".aws", ".env", "node_modules", "target", ".data", ".tools", ".m2", "dist", ".DS_Store", ".idea", ".gradle", ".classpath", ".project", "__pycache__");
                            return Stream.of(path.split("/")).noneMatch(part -> exclusions.contains(part) || part.startsWith(".env.") || part.endsWith(".class") || part.endsWith(".pyc"));
                        }).sorted().toList();
                write(root, "acceptance-added.txt", "AMBER24 new synthetic revision");
                git(root, "add", "--", "acceptance-added.txt");
                if (number == 9) {
                    git(root, "mv", "src/WinningStrategy.java", "src/RenamedStrategy.java");
                    git(root, "rm", "--quiet", "src/Player.java");
                }
                fixtures.add(new Fixture(root, original, commit(root), manifest, null));
            } catch (Exception failure) {
                fixtures.add(new Fixture(root, null, null, List.of(), failure.getClass().getSimpleName() + ": fixture preparation failed"));
            }
        }
    }

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/git-acceptance-results.json"), new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 20; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(301 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int number, int action) throws Exception {
        Fixture fixture = fixtures.get(number);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("scope", "REAL_GITHUB_COLLECTOR_GIT_BLOBS_WITH_LOCAL_CLONE_TRANSPORT");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("UI/SourceService persistence/auto-sync/error presentation", "Semantic code questions and pinned answer provenance", "Concurrent clicks, Retry-After and live GitHub failure injection", "Owner identity requires SourceService; equal-time revision fixture not separately exercised"));
        long start = System.nanoTime();
        AtomicInteger remoteCalls = new AtomicInteger();
        AtomicInteger clones = new AtomicInteger();
        AtomicReference<String> revision = new AtomicReference<>(fixture.original());
        AtomicReference<Exception> remoteFailure = new AtomicReference<>();
        AtomicReference<Exception> cloneFailure = new AtomicReference<>();
        try {
            if (fixture.setupFailure() != null) {
                result.put("status", "BLOCKED"); result.put("checkStatus", "BLOCKED");
                result.put("reason", fixture.setupFailure());
                org.junit.jupiter.api.Assumptions.assumeTrue(false, fixture.setupFailure());
            }
            result.put("revision", fixture.original());
            result.put("expectedManifest", fixture.manifest());
            String address = number == 5 ? "https://github.com/owner-a/repo" : "https://github.com/example/synthetic";
            URI expectedApi = URI.create(address.replace("https://github.com/", "https://api.github.com/repos/") + "/commits?per_page=1");
            RemoteReader remote = uri -> {
                assertThat(uri).isEqualTo(expectedApi);
                remoteCalls.incrementAndGet();
                if (remoteFailure.get() != null) throw remoteFailure.get();
                return new RemoteReader.Response(uri, "application/json", ("[{\"sha\":\"" + revision.get() + "\"}]").getBytes(StandardCharsets.UTF_8));
            };
            GitRunner runner = new GitRunner(System.getenv().getOrDefault("FORGE_ACCEPTANCE_GIT_HOME", "")) {
                @Override public byte[] run(Path working, int limit, String... arguments) throws Exception {
                    if (arguments.length > 0 && arguments[0].equals("clone")) {
                        clones.incrementAndGet();
                        if (cloneFailure.get() != null) throw cloneFailure.get();
                        assertThat(List.of(arguments).subList(0, 7)).containsExactly("clone", "--quiet", "--no-checkout", "--filter=blob:none", "--depth=1", "--single-branch", "--");
                        git(working, "clone", "--quiet", "--no-checkout", "--", fixture.root().toString(), "repository");
                        git(working.resolve("repository"), "update-ref", "HEAD", number == 6 ? fixture.next() : revision.get());
                        return new byte[0];
                    }
                    return super.run(working, limit, arguments);
                }
            };
            var collector = new GithubCollector(remote, runner);
            if (number == 6) {
                assertThatThrownBy(() -> collector.collect(address)).isInstanceOfSatisfying(LibraryException.class,
                        failure -> assertThat(failure.status()).isEqualTo(409));
                result.put("remaining", List.of("Revision mismatch rejected; subsequent success-dependent flow and UI not exercised"));
            } else if (number >= 15) {
                Exception failure = number <= 17 ? new LibraryException(502, "원격 서버 HTTP 오류: " + List.of("404", "403", "429").get(number - 15))
                        : new java.io.IOException(number == 18 ? "synthetic clone connection closed" : "synthetic Git executable unavailable");
                CollectedSnapshot previous = action == 1 ? collector.collect(address) : null;
                try {
                    if (number <= 17) remoteFailure.set(failure); else cloneFailure.set(failure);
                    if (previous != null) revision.set(fixture.next());
                    int attempts = action == 2 ? 5 : 1;
                    for (int attempt = 0; attempt < attempts; attempt++) {
                        assertThatThrownBy(() -> collector.collect(address, previous)).isSameAs(failure);
                    }
                    if (previous != null) assertThat(previous.files()).isNotEmpty();
                    if (action == 3) {
                        remoteFailure.set(null); cloneFailure.set(null);
                        try (var restored = collector.collect(address)) { assertThat(restored.files()).isNotEmpty(); }
                    }
                    result.put("injectedFailure", failure.getMessage());
                } finally { if (previous != null) previous.close(); }
            } else {
                try (var initial = collector.collect(address)) {
                    assertThat(initial.revision()).isEqualTo(fixture.original());
                    var searchableManifest = fixture.manifest().stream()
                            .filter(path -> number != 10 || !path.equals("large.java"))
                            .filter(path -> number != 11 || !path.equals("external-module")).toList();
                    assertThat(initial.files().stream().map(CollectedSnapshot.File::path).sorted()).containsExactlyElementsOf(searchableManifest);
                    result.put("actualCount", initial.files().size());
                    if (number == 11) assertThat(initial.failures()).containsKey("external-module");
                    if (number == 0) assertThat(initial.files()).anySatisfy(file -> assertThat(file.path()).endsWith("strategy/WinningStrategy.java"));
                    if (number == 1) assertThat(initial.files()).noneSatisfy(file -> assertThat(file.path()).contains("/strategy/"));
                    if (number == 10) {
                        assertThat(initial.failures()).containsKey("large.java");
                        assertThat(initial.files()).as("LFS pointers must not be published as searchable source bodies")
                                .noneSatisfy(file -> assertThat(file.path()).isEqualTo("large.java"));
                    }
                    if (number == 14) {
                        var file = initial.files().stream().filter(item -> item.path().equals("Invalid.java")).findFirst().orElseThrow();
                        var analysis = new TikaJavaAnalyzer().analyze(file.path(), file.content());
                        assertThat(analysis.status()).as("Invalid UTF-8 must not silently become READY with replacement characters").isNotEqualTo("READY");
                    }
                    if (action == 1) {
                        int textFiles = 0;
                        for (var file : initial.files()) {
                            if (!file.path().endsWith(".java")) continue;
                            try (var input = file.open()) {
                                var analysis = new TikaJavaAnalyzer().analyzeStream(file.path(), input);
                                if (!analysis.text().isBlank()) textFiles++;
                            }
                        }
                        result.put("analyzedJavaFiles", textFiles);
                        if (number != 3) assertThat(textFiles).isPositive();
                        result.put("codeQuestionScope", "Extraction only; semantic question retrieval still required");
                    } else if (action == 2) {
                        int before = clones.get();
                        var same = collector.collect(address, initial);
                        if (initial.failures().isEmpty()) {
                            assertThat(same).isSameAs(initial);
                            assertThat(clones.get()).isEqualTo(before);
                        } else {
                            assertThat(same.revision()).isEqualTo(initial.revision());
                            assertThat(same.failures()).isEqualTo(initial.failures());
                            assertThat(same.files()).extracting(CollectedSnapshot.File::hash)
                                    .containsExactlyElementsOf(initial.files().stream().map(CollectedSnapshot.File::hash).toList());
                            assertThat(clones.get()).isEqualTo(before + 1);
                        }
                    } else if (action == 3) {
                        revision.set(fixture.next());
                        try (var changed = collector.collect(address, initial)) {
                            assertThat(changed.revision()).isEqualTo(fixture.next());
                            assertThat(changed.files()).anySatisfy(file -> assertThat(file.path()).isEqualTo("acceptance-added.txt"));
                            if (number == 9) {
                                assertThat(changed.files()).extracting(CollectedSnapshot.File::path).contains("src/RenamedStrategy.java")
                                        .doesNotContain("src/WinningStrategy.java", "src/Player.java");
                            }
                        }
                    } else if (action == 4) {
                        remoteFailure.set(new LibraryException(502, "원격 서버 HTTP 오류: 503"));
                        assertThatThrownBy(() -> collector.collect(address, initial)).isInstanceOf(LibraryException.class);
                        remoteFailure.set(null);
                        var recovered = collector.collect(address, initial);
                        if (initial.failures().isEmpty()) assertThat(recovered).isSameAs(initial);
                        else {
                            assertThat(recovered.revision()).isEqualTo(initial.revision());
                            assertThat(recovered.failures()).isEqualTo(initial.failures());
                            assertThat(recovered.files()).extracting(CollectedSnapshot.File::hash)
                                    .containsExactlyElementsOf(initial.files().stream().map(CollectedSnapshot.File::hash).toList());
                        }
                        for (var file : initial.files()) try (var input = file.open()) { assertThat(input.read()).isGreaterThanOrEqualTo(-1); }
                    }
                }
            }
            result.put("status", "PARTIAL"); result.put("checkStatus", "PASS");
        } catch (org.opentest4j.TestAbortedException blocked) {
            throw blocked;
        } catch (Throwable failure) {
            result.put("status", "FAIL"); result.put("checkStatus", "FAIL");
            result.put("failure", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage().substring(0, Math.min(4000, failure.getMessage().length())));
            throw failure;
        } finally {
            result.put("remoteAdapterCalls", remoteCalls.get()); result.put("cloneCalls", clones.get());
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("GIT_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }

    private void write(Path root, String path, String text) throws Exception {
        Files.createDirectories(root.resolve(path).getParent()); Files.writeString(root.resolve(path), text);
    }

    private String commit(Path root) throws Exception {
        git(root, "-c", "user.name=Synthetic", "-c", "user.email=synthetic@example.test", "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "Owned acceptance fixture");
        return new String(git(root, "rev-parse", "HEAD"), StandardCharsets.UTF_8).strip();
    }

    private byte[] git(Path root, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "init.templateDir=", "-c", "credential.helper="));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(directory, "git-output-", ".txt");
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1"); builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Fixture Git timeout");
            if (process.exitValue() != 0) throw new java.io.IOException("Fixture Git exit " + process.exitValue());
            if (Files.size(output) > 8 * 1024 * 1024) throw new java.io.IOException("Fixture Git output limit");
            return Files.readAllBytes(output);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(output);
        }
    }
}
