package dev.dougle.sources.adapter;

import dev.dougle.ingestion.application.ProcessingProgress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class LocalFolderCollectorBoundaryMatrixTest {
    @TempDir Path directory;

    static Stream<Arguments> pathBoundaries() {
        return Stream.of(1, 16, 17).flatMap(depth -> Stream.of(119, 120, 121)
                .flatMap(length -> Stream.of("a", "가", " ")
                        .map(prefix -> Arguments.of(depth, length, prefix))));
    }

    @ParameterizedTest(name = "LFC-PATH-{index}: depth={0} nameLength={1} prefix=[{2}]")
    @MethodSource("pathBoundaries")
    void rejectedPathsDoNotDiscardHealthySibling(int depth, int length, String prefix) throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        String filename = prefix + "x".repeat(length - prefix.length() - 4) + ".txt";
        String relative = "level/".repeat(depth - 1) + filename;
        Path candidate = root.resolve(relative);
        Files.createDirectories(candidate.getParent());
        byte[] original = "synthetic boundary 가상".getBytes(StandardCharsets.UTF_8);
        Files.write(candidate, original);
        Path healthy = Files.writeString(root.resolve("healthy.txt"), "synthetic healthy");
        var modified = Files.getLastModifiedTime(candidate);
        boolean accepted = depth <= 16 && length <= 120;
        try (var snapshot = new LocalFolderCollector().collect(root.toString())) {
            assertThat(snapshot.files()).hasSize(accepted ? 2 : 1);
            assertThat(snapshot.files()).anySatisfy(file -> {
                assertThat(file.path()).isEqualTo("healthy.txt");
                assertThat(file.content()).containsExactly("synthetic healthy".getBytes(StandardCharsets.UTF_8));
            });
            if (accepted) {
                assertThat(snapshot.failures()).isEmpty();
                assertThat(snapshot.files()).anySatisfy(file -> {
                    assertThat(Normalizer.normalize(file.path(), Normalizer.Form.NFC)).isEqualTo(relative);
                    assertThat(file.content()).containsExactly(original);
                });
            } else {
                assertThat(snapshot.failures().keySet()).extracting(path -> Normalizer.normalize(path, Normalizer.Form.NFC))
                        .containsExactly(relative);
                assertThat(snapshot.failures().values()).allSatisfy(message ->
                        assertThat(message).contains(depth > 16 ? "깊이" : "경로"));
            }
        }
        assertThat(Files.readAllBytes(candidate)).containsExactly(original);
        assertThat(Files.getLastModifiedTime(candidate)).isEqualTo(modified);
        assertThat(Files.readString(healthy)).isEqualTo("synthetic healthy");
    }

    static Stream<Arguments> symlinks() {
        return Stream.of("FILE", "DIRECTORY", "MISSING").flatMap(target ->
                Stream.of("alias", "가상 링크", "ALIAS WITH SPACE").map(name -> Arguments.of(target, name)));
    }

    @ParameterizedTest(name = "LFC-LINK-{index}: target={0} name={1}")
    @MethodSource("symlinks")
    void doesNotFollowExternalOrBrokenSymlinks(String targetKind, String name) throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("private.txt"), "synthetic outside marker");
        Path target = switch (targetKind) {
            case "FILE" -> outsideFile;
            case "DIRECTORY" -> outside;
            default -> outside.resolve("missing");
        };
        Path link = Files.createSymbolicLink(root.resolve(name), target);
        Files.writeString(root.resolve("healthy.txt"), "synthetic healthy");
        try (var snapshot = new LocalFolderCollector().collect(root.toString())) {
            assertThat(snapshot.files()).extracting(file -> file.path()).containsExactly("healthy.txt");
            assertThat(snapshot.files().getFirst().content()).containsExactly("synthetic healthy".getBytes(StandardCharsets.UTF_8));
            assertThat(snapshot.failures().keySet()).extracting(path -> Normalizer.normalize(path, Normalizer.Form.NFC))
                    .containsExactly(name);
            assertThat(snapshot.failures().values()).allSatisfy(message -> assertThat(message).contains("심볼릭"));
        }
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(target);
        assertThat(Files.readString(outsideFile)).isEqualTo("synthetic outside marker");
        assertThat(Files.exists(outside.resolve("missing"))).isFalse();
    }

    @ParameterizedTest(name = "LFC-CANCEL-{index}: phase={0}")
    @ValueSource(strings = {"BEFORE_WALK", "READING", "READING_BYTES"})
    void cancellationPreservesOriginalAndAllowsRetry(String phase) throws Exception {
        byte[] original = "synthetic cancellation\n".repeat(4000).getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(directory.resolve("synthetic.txt"), original);
        var modified = Files.getLastModifiedTime(file);
        var events = new AtomicInteger();
        var collector = new LocalFolderCollector();
        try (var observation = ProcessingProgress.observe(step -> {
            events.incrementAndGet();
            if (step.stage().equals(phase)) throw new CancellationException("synthetic cancellation");
        })) {
            if (phase.equals("BEFORE_WALK")) {
                try {
                    Thread.currentThread().interrupt();
                    assertThatThrownBy(() -> collector.collect(directory.toString())).isInstanceOf(IOException.class);
                    assertThat(events.get()).isZero();
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally {
                    Thread.interrupted();
                }
            } else {
                assertThatThrownBy(() -> collector.collect(directory.toString())).isInstanceOf(CancellationException.class);
                assertThat(events.get()).isPositive();
            }
        }
        assertThat(Files.readAllBytes(file)).containsExactly(original);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        try (var snapshot = collector.collect(directory.toString())) {
            assertThat(snapshot.failures()).isEmpty();
            assertThat(snapshot.files()).hasSize(1);
            assertThat(snapshot.files().getFirst().content()).containsExactly(original);
        }
    }
}
