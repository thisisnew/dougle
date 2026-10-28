package dev.dougle.ingestion.adapter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

final class ExtractedTextWriter extends Writer {
    static final int PART_SIZE = 64 * 1024;
    static final long MAX_SPOOL_BYTES = 512L * 1024 * 1024;
    private static final int INLINE_SIZE = 2_000_000;
    private final long maxBytes;
    private final StringBuilder buffer = new StringBuilder();
    private final List<Long> offsets = new ArrayList<>();
    private Path path;
    private RandomAccessFile file;
    private boolean previousCr;
    private boolean transferred;

    ExtractedTextWriter() { this(MAX_SPOOL_BYTES); }

    ExtractedTextWriter(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Extraction budget must be positive");
        this.maxBytes = maxBytes;
    }

    static final class SizeLimitException extends IOException {
        SizeLimitException() { super("Extracted text exceeds the storage budget"); }
    }

    @Override public void write(char[] characters, int start, int length) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        for (int index = start; index < start + length; index++) {
            char character = characters[index];
            if (character == '\u0000') continue;
            if (character == '\n' && previousCr) { previousCr = false; continue; }
            previousCr = character == '\r';
            buffer.append(previousCr ? '\n' : character);
            if (buffer.length() >= (file == null ? INLINE_SIZE : PART_SIZE)
                    && !Character.isHighSurrogate(character)) {
                while (buffer.length() >= PART_SIZE) spill(false);
            }
        }
    }

    private void spill(boolean finished) throws IOException {
        if (file == null) {
            path = Files.createTempFile("forge-extracted-", ".text");
            file = new RandomAccessFile(path.toFile(), "rw");
        }
        int end = Math.min(PART_SIZE, buffer.length());
        if (end < buffer.length() && Character.isLowSurrogate(buffer.charAt(end))) end--;
        if (!finished) {
            if (end == buffer.length()) end = buffer.offsetByCodePoints(end, -1);
            while (end > 0 && !normalizationBoundary(buffer.codePointAt(end))) {
                end = buffer.offsetByCodePoints(end, -1);
            }
            if (end == 0) throw new IOException("Unicode combining sequence exceeds the extraction window");
        }
        String text = java.text.Normalizer.normalize(buffer.substring(0, end), java.text.Normalizer.Form.NFC);
        for (int start = 0; start < text.length();) {
            int next = Math.min(start + PART_SIZE, text.length());
            if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) next--;
            appendPart(text.substring(start, next));
            start = next;
        }
        buffer.delete(0, end);
    }

    private boolean normalizationBoundary(int codePoint) {
        int type = Character.getType(codePoint);
        return type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
                && type != Character.ENCLOSING_MARK && !(codePoint >= 0x1160 && codePoint <= 0x11ff)
                && !(codePoint >= 0xd7b0 && codePoint <= 0xd7ff);
    }

    private void appendPart(String text) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length + (long) Integer.BYTES > maxBytes - file.getFilePointer()) throw new SizeLimitException();
        offsets.add(file.getFilePointer());
        file.writeInt(bytes.length);
        file.write(bytes);
    }

    List<String> finish() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        if (file == null) {
            String text = java.text.Normalizer.normalize(buffer, java.text.Normalizer.Form.NFC).strip();
            if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new SizeLimitException();
            return List.of(text);
        }
        if (!buffer.isEmpty()) spill(true);
        file.close();
        file = null;
        transferred = true;
        return new Spool(path, List.copyOf(offsets));
    }

    @Override public void flush() {}
    @Override public void close() throws IOException {
        if (file != null) file.close();
        if (!transferred && path != null) Files.deleteIfExists(path);
    }

    private static final class Spool extends AbstractList<String> implements AutoCloseable {
        private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();
        private final Path path;
        private final List<Long> offsets;
        private final java.lang.ref.Cleaner.Cleanable cleanup;
        Spool(Path path, List<Long> offsets) {
            this.path = path;
            this.offsets = offsets;
            cleanup = CLEANER.register(this, () -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        }
        @Override public int size() { return offsets.size(); }
        @Override public String get(int index) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            try (var input = new RandomAccessFile(path.toFile(), "r")) {
                input.seek(offsets.get(index));
                byte[] bytes = new byte[input.readInt()];
                input.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException exception) { throw new UncheckedIOException(exception); }
        }
        @Override public void close() { cleanup.clean(); }
    }
}
