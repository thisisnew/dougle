package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.application.Collector;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class LocalFolderCollector implements Collector {
    public String kind() { return "LOCAL_FOLDER"; }

    public void validate(String location) {
        try {
            if (!Path.of(location).isAbsolute()) throw new InvalidPathException(location, "absolute path required");
        } catch (InvalidPathException exception) {
            throw new LibraryException(400, "로컬 폴더의 절대 경로를 입력하세요.");
        }
    }

    public CollectedSnapshot collect(String location) throws IOException {
        validate(location);
        Path requested = Path.of(location).normalize();
        if (Files.isSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new LibraryException(400, "심볼릭 링크가 아닌 실제 폴더를 선택하세요.");
        }
        Path root = requested.toRealPath();
        var files = new ArrayList<CollectedSnapshot.File>();
        long[] total = {0};
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void budget() throws IOException {
                if (System.nanoTime() > deadline || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Collection timeout or interruption");
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                budget();
                if (!directory.equals(root)) {
                    if (CollectionLimits.excluded(directory.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    CollectionLimits.path(root.relativize(directory).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget();
                if (CollectionLimits.excluded(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root)) {
                    throw new LibraryException(400, "심볼릭 링크·특수 파일은 수집하지 않습니다.");
                }
                if (attributes.size() > CollectionLimits.MAX_FILE || files.size() >= CollectionLimits.MAX_FILES) {
                    throw new LibraryException(413, "파일 크기 또는 개수 제한을 초과했습니다.");
                }
                byte[] content;
                try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     var input = Channels.newInputStream(channel)) {
                    content = CollectionLimits.read(input);
                }
                total[0] += content.length;
                if (total[0] > CollectionLimits.MAX_TOTAL) throw new LibraryException(413, "Source 총 50 MiB 제한을 초과했습니다.");
                files.add(new CollectedSnapshot.File(root.relativize(file).toString().replace('\\', '/'), content));
                return FileVisitResult.CONTINUE;
            }
        });
        return CollectionLimits.snapshot(files);
    }
}
