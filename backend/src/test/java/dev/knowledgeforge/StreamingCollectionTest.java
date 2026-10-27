package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.sources.adapter.CollectionLimits;
import dev.knowledgeforge.sources.adapter.LocalFolderCollector;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StreamingCollectionTest {
    @TempDir Path directory;

    @Test
    void streamsLargeTextThroughItsFinalParagraphAndDoesNotModifyOriginal() throws Exception {
        Path original = directory.resolve("large.txt");
        try (var writer = Files.newBufferedWriter(original)) {
            for (int index = 0; index < 400_000; index++) writer.write("Synthetic greenhouse observation.\n");
            writer.write("FINAL_SYNTHETIC_EVIDENCE");
        }
        long size = Files.size(original);
        assertThat(size).isGreaterThan(CollectionLimits.MAX_FILE);
        CollectedSnapshot.File captured;
        try (var snapshot = new LocalFolderCollector().collect(directory.toString())) {
            captured = snapshot.files().getFirst();
            assertThat(captured.size()).isEqualTo(size);
            try (var input = captured.open(); var result = new TikaJavaAnalyzer().analyzeStream("large.txt", input)) {
                assertThat(result.status()).isEqualTo("READY");
                assertThat(result.textParts().getLast().stripTrailing()).endsWith("FINAL_SYNTHETIC_EVIDENCE");
                assertThat(result.textParts().stream().mapToLong(String::length).sum()).isGreaterThan(13_000_000);
            }
            assertThat(Files.size(original)).isEqualTo(size);
        }
        assertThatThrownBy(captured::open).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void oversizedFileDoesNotDiscardReadableSibling() throws Exception {
        Files.writeString(directory.resolve("readable.txt"), "Synthetic observations");
        try (var sparse = new java.io.RandomAccessFile(directory.resolve("oversized.bin").toFile(), "rw")) {
            sparse.setLength(CollectionLimits.MAX_DOCUMENT + 1);
        }
        try (var snapshot = new LocalFolderCollector().collect(directory.toString())) {
            assertThat(snapshot.files()).extracting(CollectedSnapshot.File::path).containsExactly("readable.txt");
            assertThat(snapshot.failures()).containsKey("oversized.bin");
        }
    }

    @Test
    void digestIsIndependentOfCopyBufferAndCancellationClosesTemporaryFile() throws Exception {
        byte[] bytes = "가상 관측 문서".repeat(20_000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var file = CollectedSnapshot.File.copy("notes.txt", new java.io.ByteArrayInputStream(bytes), bytes.length)) {
            assertThat(file.hash()).isEqualTo(CollectionLimits.hash(bytes));
            assertThat(file.content()).isEqualTo(bytes);
        }
        assertThatThrownBy(() -> CollectedSnapshot.File.copy("notes.txt", new java.io.ByteArrayInputStream(bytes), bytes.length,
                count -> { throw new java.util.concurrent.CancellationException(); }))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
