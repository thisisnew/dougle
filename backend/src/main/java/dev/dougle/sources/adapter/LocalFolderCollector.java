package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.application.Collector;
import dev.dougle.sources.domain.CollectedSnapshot;
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
            throw new LibraryException(400, "연결할 폴더를 선택하세요.");
        }
        Path requested = Path.of(location).normalize();
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new LibraryException(400, "폴더가 없거나 접근할 수 없습니다. 폴더 위치와 macOS의 Dougle 파일 접근 권한을 확인하세요.");
        }
        if (!Files.isReadable(requested)) {
            throw new LibraryException(400, "폴더 읽기 권한이 없습니다. macOS 시스템 설정에서 Dougle의 파일 및 폴더 접근 권한을 확인하세요.");
        }
        if (Files.isSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new LibraryException(400, "폴더를 선택하세요. PDF 등 개별 파일은 ‘자료 추가 → 파일’을 이용하세요. 기존 연결이 잘못됐다면 올바른 폴더로 새로 연결하세요.");
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
        var failures = new java.util.HashMap<String, String>();
        long[] total = {0};
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos();
        try {
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
                    if (!supported(directory)) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget();
                dev.dougle.ingestion.application.ProcessingProgress.report("READING",
                        root.relativize(file).toString(), files.size(), 0);
                if (CollectionLimits.excluded(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                if (!supported(file)) return FileVisitResult.CONTINUE;
                if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                    failures.put(root.relativize(file).toString(), "심볼릭 링크·특수 파일은 읽지 않습니다. 필요한 원본의 실제 폴더를 연결하세요.");
                    return FileVisitResult.CONTINUE;
                }
                try {
                    if (!file.toRealPath().startsWith(root)) {
                        throw new LibraryException(400, "연결한 폴더 밖의 파일은 수집하지 않습니다.");
                    }
                } catch (NoSuchFileException | AccessDeniedException exception) {
                    failures.put(root.relativize(file).toString().replace('\\', '/'),
                            "파일이 이동·삭제되었거나 읽을 수 없습니다. 위치·권한을 확인하고 다시 동기화하세요.");
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.size() > CollectionLimits.MAX_DOCUMENT) {
                    failures.put(root.relativize(file).toString().replace('\\', '/'), "256 MiB가 넘는 파일은 나눠 등록하세요.");
                    return FileVisitResult.CONTINUE;
                }
                if (files.size() >= CollectionLimits.MAX_SNAPSHOT_FILES) {
                    throw new LibraryException(413, "파일이 20,000개를 초과했습니다. 폴더를 나눠 연결하세요.");
                }
                CollectedSnapshot.File collected;
                try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                     var input = Channels.newInputStream(channel)) {
                    collected = CollectedSnapshot.File.copy(root.relativize(file).toString().replace('\\', '/'),
                            input, CollectionLimits.MAX_DOCUMENT,
                            bytes -> dev.dougle.ingestion.application.ProcessingProgress.report(
                                    "READING_BYTES", root.relativize(file).toString(), bytes, attributes.size()));
                } catch (java.io.InterruptedIOException exception) {
                    throw exception;
                } catch (IOException exception) {
                    failures.put(root.relativize(file).toString().replace('\\', '/'), "파일 읽기 실패 · 접근 권한을 확인하고 다시 동기화하세요.");
                    return FileVisitResult.CONTINUE;
                }
                files.add(collected);
                total[0] += collected.size();
                if (total[0] > CollectionLimits.MAX_SNAPSHOT) throw new LibraryException(413, "총 8 GiB를 초과했습니다. 폴더를 나눠 연결하세요.");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                budget();
                if (file.equals(root)) throw exception;
                String path = root.relativize(file).toString().replace('\\', '/');
                failures.put(path, "접근할 수 없습니다. 읽기 권한을 확인하고 다시 동기화하세요.");
                return FileVisitResult.CONTINUE;
            }

            private boolean supported(Path path) {
                String relative = root.relativize(path).toString();
                try {
                    CollectionLimits.path(relative);
                    return true;
                } catch (LibraryException exception) {
                    failures.put(relative, exception.getMessage());
                    return false;
                }
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                budget();
                if (exception != null) {
                    if (directory.equals(root)) throw exception;
                    failures.put(root.relativize(directory).toString(), "폴더를 끝까지 읽지 못했습니다. 읽기 권한을 확인하고 다시 동기화하세요.");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        var snapshot = CollectionLimits.snapshot(files);
        return new CollectedSnapshot(snapshot.revision(), snapshot.files(), null, failures);
        } catch (IOException | RuntimeException exception) {
            files.forEach(CollectedSnapshot.File::close);
            throw exception;
        }
    }
}
