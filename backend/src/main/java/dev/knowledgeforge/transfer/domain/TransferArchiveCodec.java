package dev.knowledgeforge.transfer.domain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipInputStream;

public final class TransferArchiveCodec {
    public static final int MAX_DOCUMENTS = 64;
    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = 9 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 4096;
    public static final int MAX_COMPRESSION_RATIO = 100;
    private static final String MANIFEST = "manifest.bin";
    private static final int MAGIC = 0x4b465441;

    public record Archive(TransferManifest manifest, Map<UUID, byte[]> documents) {
        public Archive { documents = copy(documents); }
        @Override public Map<UUID, byte[]> documents() { return copy(documents); }
    }

    public byte[] write(Map<UUID, byte[]> documents) throws IOException {
        if (documents == null || documents.size() > MAX_DOCUMENTS) throw invalid("Too many documents");
        long total = 0;
        for (var entry : documents.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().length > MAX_DOCUMENT_BYTES) {
                throw invalid("Invalid document size or identifier");
            }
            total += entry.getValue().length;
            if (total > MAX_TOTAL_BYTES) throw invalid("Total document size exceeded");
        }
        var snapshot = copy(documents);
        var descriptors = snapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new TransferManifest.Document(entry.getKey(), entry.getValue().length, digest(entry.getValue())))
                .toList();
        var manifest = new TransferManifest(TransferManifest.VERSION, descriptors);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, MANIFEST, encode(manifest));
            for (var document : descriptors) put(zip, document.path(), snapshot.get(document.id()));
        }
        if (output.size() > MAX_ARCHIVE_BYTES) throw invalid("Archive size exceeded");
        return output.toByteArray();
    }

    public Archive read(byte[] input) throws IOException {
        if (input.length > MAX_ARCHIVE_BYTES) throw invalid("Archive size exceeded");
        return read(new ByteArrayInputStream(input.clone()));
    }

    public Archive read(InputStream input) throws IOException {
        var entries = readZip(input);
        byte[] manifestBytes = entries.remove(MANIFEST);
        if (manifestBytes == null) throw invalid("Missing manifest");
        var manifest = decode(manifestBytes);
        if (entries.size() != manifest.documents().size()) throw invalid("Manifest entry set mismatch");
        var documents = new LinkedHashMap<UUID, byte[]>();
        for (var document : manifest.documents()) {
            byte[] content = entries.remove(document.path());
            if (content == null || content.length != document.size() || !digest(content).equals(document.sha256())) {
                throw invalid("Document checksum, size or path mismatch");
            }
            documents.put(document.id(), content);
        }
        return new Archive(manifest, documents);
    }

    private Map<String, byte[]> readZip(InputStream input) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        var bounded = new ArchiveInput(input);
        long documentBytes = 0;
        try (var zip = new ZipInputStream(bounded, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entries.size() >= MAX_DOCUMENTS + 1) throw invalid("Too many ZIP entries");
                if (entry.isDirectory() || !allowedPath(name)) throw invalid("Disallowed archive path");
                if (entries.containsKey(name)) throw invalid("Duplicate ZIP entry");
                if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                    throw invalid("Unsupported ZIP compression");
                }
                boolean manifest = name.equals(MANIFEST);
                int limit = manifest ? MAX_MANIFEST_BYTES : MAX_DOCUMENT_BYTES;
                if (entry.getSize() > limit) throw invalid("Entry size exceeded");
                var output = new ByteArrayOutputStream();
                int length;
                while ((length = zip.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1))) != -1) {
                    if (output.size() + length > limit) throw invalid("Entry size exceeded");
                    if (!manifest) {
                        documentBytes += length;
                        if (documentBytes > MAX_TOTAL_BYTES) throw invalid("Total document size exceeded");
                    }
                    output.write(buffer, 0, length);
                }
                long compressed = entry.getCompressedSize();
                if (compressed < 0 || (output.size() > 0 && compressed == 0)
                        || (compressed > 0 && (double) output.size() / compressed > MAX_COMPRESSION_RATIO)) {
                    throw invalid("Compression ratio exceeded or unavailable");
                }
                entries.put(name, output.toByteArray());
            }
            while (bounded.read(buffer) != -1) { }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid ZIP encoding", exception);
        }
        return entries;
    }

    private static boolean allowedPath(String path) {
        return path.equals(MANIFEST) || path.matches("documents/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin");
    }

    private static final class ArchiveInput extends FilterInputStream {
        private int consumed;

        private ArchiveInput(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++consumed > MAX_ARCHIVE_BYTES) throw invalid("Archive size exceeded");
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, Math.min(length, MAX_ARCHIVE_BYTES - consumed + 1));
            if (count > 0) {
                consumed += count;
                if (consumed > MAX_ARCHIVE_BYTES) throw invalid("Archive size exceeded");
            }
            return count;
        }

        @Override public void close() { }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        var entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        var crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] encode(TransferManifest manifest) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(output)) {
            data.writeInt(MAGIC);
            data.writeInt(manifest.version());
            data.writeInt(manifest.documents().size());
            for (var document : manifest.documents()) {
                data.writeLong(document.id().getMostSignificantBits());
                data.writeLong(document.id().getLeastSignificantBits());
                data.writeInt(document.size());
                data.write(HexFormat.of().parseHex(document.sha256()));
            }
        }
        return output.toByteArray();
    }

    private static TransferManifest decode(byte[] bytes) throws IOException {
        try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (data.readInt() != MAGIC) throw invalid("Unsupported manifest format");
            int version = data.readInt();
            int count = data.readInt();
            if (version != TransferManifest.VERSION || count < 0 || count > MAX_DOCUMENTS
                    || bytes.length != 12 + count * 52) throw invalid("Unsupported manifest version or layout");
            var documents = new ArrayList<TransferManifest.Document>();
            for (int index = 0; index < count; index++) {
                var id = new UUID(data.readLong(), data.readLong());
                int size = data.readInt();
                documents.add(new TransferManifest.Document(id, size, HexFormat.of().formatHex(data.readNBytes(32))));
            }
            return new TransferManifest(version, documents);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid transfer manifest", exception);
        }
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<UUID, byte[]> copy(Map<UUID, byte[]> documents) {
        var result = new LinkedHashMap<UUID, byte[]>();
        documents.forEach((id, content) -> result.put(id, content.clone()));
        return Collections.unmodifiableMap(result);
    }

    private static IOException invalid(String message) { return new IOException(message); }
}
