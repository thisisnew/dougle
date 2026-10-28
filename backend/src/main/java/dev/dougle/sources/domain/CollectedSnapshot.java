package dev.dougle.sources.domain;

import java.util.List;

public record CollectedSnapshot(String revision, List<File> files, HttpValidators validators,
                                java.util.Map<String, String> failures) implements AutoCloseable {
    public static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    public CollectedSnapshot {
        files = List.copyOf(files);
        failures = java.util.Map.copyOf(failures);
    }
    public CollectedSnapshot(String revision, List<File> files, HttpValidators validators) {
        this(revision, files, validators, java.util.Map.of());
    }
    public CollectedSnapshot(String revision, List<File> files) { this(revision, files, null); }
    public void close() {
        files.forEach(File::close);
    }

    public static final class File implements AutoCloseable {
        private final String path;
        private final byte[] bytes;
        private final java.nio.file.Path storage;
        private final long size;
        private final String hash;

        public File(String path, byte[] content) {
            this.path = path;
            this.bytes = content.clone();
            this.storage = null;
            this.size = content.length;
            this.hash = digest(new java.io.ByteArrayInputStream(this.bytes));
        }

        private File(String path, java.nio.file.Path storage, long size, String hash) {
            this.path = path;
            this.bytes = null;
            this.storage = storage;
            this.size = size;
            this.hash = hash;
        }

        public static File copy(String path, java.io.InputStream input, long limit) throws java.io.IOException {
            return copy(path, input, limit, ignored -> {});
        }

        public static File copy(String path, java.io.InputStream input, long limit,
                                java.util.function.LongConsumer progress) throws java.io.IOException {
            java.nio.file.Path storage = java.nio.file.Files.createTempFile("dougle-snapshot-", ".bin");
            boolean complete = false;
            try {
                var digest = sha256();
                long size = 0;
                try (var output = java.nio.file.Files.newOutputStream(storage)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        size += count;
                        if (size > limit) throw new java.io.IOException("File exceeds processing limit");
                        progress.accept(size);
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                }
                complete = true;
                return new File(path, storage, size, java.util.HexFormat.of().formatHex(digest.digest()));
            } finally {
                if (!complete) java.nio.file.Files.deleteIfExists(storage);
            }
        }

        public String path() { return path; }
        public long size() { return size; }
        public String hash() { return hash; }

        public java.io.InputStream open() throws java.io.IOException {
            return storage == null ? new java.io.ByteArrayInputStream(bytes) : java.nio.file.Files.newInputStream(storage);
        }

        public byte[] content() {
            try (var input = open()) {
                return input.readAllBytes();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }

        public void close() {
            if (storage != null) {
                try {
                    java.nio.file.Files.deleteIfExists(storage);
                } catch (java.io.IOException exception) {
                    storage.toFile().deleteOnExit();
                }
            }
        }

        private static java.security.MessageDigest sha256() {
            try {
                return java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String digest(java.io.InputStream input) {
            try (input) {
                var digest = sha256();
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                return java.util.HexFormat.of().formatHex(digest.digest());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        }
    }
}
