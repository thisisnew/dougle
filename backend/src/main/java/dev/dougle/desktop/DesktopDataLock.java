package dev.dougle.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class DesktopDataLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private DesktopDataLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static DesktopDataLock acquire(String directory) throws IOException {
        if (directory == null || directory.isBlank() || !Path.of(directory).isAbsolute()) {
            throw new IllegalStateException("Desktop mode requires absolute FORGE_DATA_DIR");
        }
        Path root = Path.of(directory).normalize();
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Desktop data directory must not be a symlink");
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path lockPath = root.resolve(".desktop.lock");
        FileChannel channel = FileChannel.open(lockPath,
                java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("Desktop data directory is already in use");
            return new DesktopDataLock(channel, lock);
        } catch (RuntimeException | IOException exception) {
            channel.close();
            throw new IllegalStateException("Cannot lock desktop data directory");
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
