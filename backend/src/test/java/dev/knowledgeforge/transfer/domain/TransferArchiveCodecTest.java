package dev.knowledgeforge.transfer.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TransferArchiveCodecTest {
    private final TransferArchiveCodec codec = new TransferArchiveCodec();
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    @Test void roundTripsSyntheticDocumentsWithTypedManifestAndDefensiveCopies() throws Exception {
        byte[] content = "synthetic 문서".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = codec.write(Map.of(FIRST, content, SECOND, new byte[0]));
        var decoded = codec.read(new ByteArrayInputStream(encoded));
        assertEquals(1, decoded.manifest().version());
        assertEquals(2, decoded.manifest().documents().size());
        assertArrayEquals(content, decoded.documents().get(FIRST));
        assertEquals(64, decoded.manifest().documents().getFirst().sha256().length());
        decoded.documents().get(FIRST)[0] = 0;
        assertArrayEquals(content, decoded.documents().get(FIRST));
        assertThrows(UnsupportedOperationException.class, () -> decoded.documents().clear());
        assertArrayEquals(encoded, codec.write(Map.of(SECOND, new byte[0], FIRST, content)));
    }

    @Test void acceptsEmptyArchiveAndExactCountAndByteBoundaries() throws Exception {
        assertTrue(codec.read(codec.write(Map.of())).documents().isEmpty());
        var documents = new LinkedHashMap<UUID, byte[]>();
        for (int index = 0; index < 64; index++) documents.put(new UUID(0, index), new byte[0]);
        assertEquals(64, codec.read(codec.write(documents)).documents().size());
        documents.clear();
        for (int index = 0; index < 8; index++) documents.put(new UUID(0, index), new byte[1024 * 1024]);
        assertEquals(8, codec.read(codec.write(documents)).documents().size());
    }

    @Test void writerRejectsCountPerFileAndTotalOverflow() {
        assertThrows(IOException.class, () -> codec.write(Map.of(FIRST, new byte[1024 * 1024 + 1])));
        var documents = new LinkedHashMap<UUID, byte[]>();
        for (int index = 0; index < 65; index++) documents.put(new UUID(0, index), new byte[0]);
        assertThrows(IOException.class, () -> codec.write(documents));
        documents.clear();
        for (int index = 0; index < 9; index++) documents.put(new UUID(0, index), new byte[1024 * 1024]);
        assertThrows(IOException.class, () -> codec.write(documents));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "C:/absolute", "C:\\absolute", "documents\\file",
            "documents/../escape", "documents//file", "./manifest.bin", "settings.json", "credentials.json",
            "documents/", "https://example.invalid/data", "documents/%2e%2e/escape", "MANIFEST.BIN"})
    void rejectsEveryPathOutsideTheTypedDocumentAllowlist(String path) throws Exception {
        assertThrows(IOException.class, () -> codec.read(zip(Map.of(path, new byte[0]), false)));
    }

    @Test void acceptsBoundedDeflateAndRejectsExcessiveCompressionRatio() throws Exception {
        var entries = entries(valid());
        assertArrayEquals(new byte[] {1, 2, 3}, codec.read(zip(entries, true)).documents().get(FIRST));
        entries.put(path(FIRST), new byte[1024 * 1024]);
        IOException failure = assertThrows(IOException.class, () -> codec.read(zip(entries, true)));
        assertTrue(failure.getMessage().contains("ratio"));
    }

    @Test void neverInterpretsCentralSymlinkAttributesAsFilesystemOperations() throws Exception {
        byte[] bytes = valid();
        little(bytes).putInt(central(bytes) + 38, 0xa1ff0000);
        little(bytes).putShort(central(bytes) + 4, (short) 0x0314);
        assertArrayEquals(new byte[] {1, 2, 3}, codec.read(bytes).documents().get(FIRST));
    }

    @Test void rejectsDuplicateZipNamesAndDuplicateManifestIdentifiers() throws Exception {
        byte[] bytes = codec.write(Map.of(FIRST, new byte[0], SECOND, new byte[0]));
        byte[] before = SECOND.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] after = FIRST.toString().getBytes(StandardCharsets.US_ASCII);
        replace(bytes, before, after);
        assertThrows(IOException.class, () -> codec.read(bytes));
        var entries = entries(codec.write(Map.of(FIRST, new byte[0], SECOND, new byte[0])));
        byte[] manifest = entries.get("manifest.bin");
        System.arraycopy(manifest, 12, manifest, 64, 16);
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
    }

    @Test void rejectsMissingExtraAndUnlistedFiles() throws Exception {
        var original = entries(valid());
        var missingManifest = new LinkedHashMap<>(original);
        missingManifest.remove("manifest.bin");
        assertThrows(IOException.class, () -> codec.read(zip(missingManifest, false)));
        var missingDocument = new LinkedHashMap<>(original);
        missingDocument.remove(path(FIRST));
        assertThrows(IOException.class, () -> codec.read(zip(missingDocument, false)));
        original.put(path(SECOND), new byte[0]);
        assertThrows(IOException.class, () -> codec.read(zip(original, false)));
        original.remove(path(FIRST));
        assertThrows(IOException.class, () -> codec.read(zip(original, false)));
    }

    @Test void rejectsCrcTamperingAndShaTamperingEvenWithRecomputedZipCrc() throws Exception {
        byte[] bytes = valid();
        int documentHeader = 30 + "manifest.bin".length() + 64;
        bytes[documentHeader + 30 + path(FIRST).length()] ^= 1;
        assertThrows(IOException.class, () -> codec.read(bytes));
        var entries = entries(valid());
        entries.get(path(FIRST))[0] ^= 1;
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
        var checksumEntries = entries(valid());
        checksumEntries.get("manifest.bin")[32] ^= 1;
        byte[] changed = zip(checksumEntries, false);
        assertThrows(IOException.class, () -> codec.read(changed));
    }

    @Test void rejectsUnknownManifestVersionMagicLayoutAndInvalidSizes() throws Exception {
        for (int offset : new int[] {0, 4, 8, 28}) {
            var entries = entries(valid());
            ByteBuffer.wrap(entries.get("manifest.bin")).putInt(offset, -1);
            byte[] changed = zip(entries, false);
            assertThrows(IOException.class, () -> codec.read(changed));
        }
        var entries = entries(valid());
        entries.put("manifest.bin", Arrays.copyOf(entries.get("manifest.bin"), 65));
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
        assertThrows(IOException.class, () -> codec.read(zip(Map.of("manifest.bin", new byte[4097]), false)));
    }

    @Test void rejectsTruncatedPayloadUnsupportedCompressionAndNonZipInput() throws Exception {
        byte[] original = valid();
        byte[] truncated = Arrays.copyOf(original, central(original) - 1);
        assertThrows(IOException.class, () -> codec.read(truncated));
        byte[] unsupported = original.clone();
        little(unsupported).putShort(8, (short) 99);
        assertThrows(IOException.class, () -> codec.read(unsupported));
        assertThrows(IOException.class, () -> codec.read(new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> codec.read(new byte[0]));
    }

    @Test void centralDirectoryIsNotAnIntegrityOrMembershipAuthority() throws Exception {
        byte[] original = valid();
        assertEquals(1, codec.read(Arrays.copyOf(original, central(original))).documents().size());
    }

    @Test void readerEnforcesCountEntryAndTotalLimitsBeforeReturningData() throws Exception {
        var entries = entries(valid());
        entries.put(path(FIRST), new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
        entries.clear();
        for (int index = 0; index < 66; index++) entries.put(path(new UUID(0, index)), new byte[0]);
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
        entries.clear();
        for (int index = 0; index < 9; index++) entries.put(path(new UUID(0, index)), new byte[1024 * 1024]);
        assertThrows(IOException.class, () -> codec.read(zip(entries, false)));
        var descriptors = new java.util.ArrayList<TransferManifest.Document>();
        for (int index = 0; index < 9; index++) {
            descriptors.add(new TransferManifest.Document(new UUID(0, index), 1024 * 1024, "0".repeat(64)));
        }
        assertThrows(IllegalArgumentException.class, () -> new TransferManifest(1, descriptors));
    }

    @Test void streamReadBoundsTrailingBytesAndDoesNotCloseCallerStream() throws Exception {
        byte[] archive = valid();
        var input = new InputStream() {
            int consumed;
            @Override public int read() {
                int position = consumed++;
                return position < archive.length ? Byte.toUnsignedInt(archive[position]) : 0;
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                for (int index = 0; index < length; index++) buffer[offset + index] = (byte) read();
                return length;
            }
            @Override public void close() { fail("Caller owns stream"); }
        };
        assertThrows(IOException.class, () -> codec.read(input));
        assertEquals(TransferArchiveCodec.MAX_ARCHIVE_BYTES + 1, input.consumed);
    }

    private byte[] valid() throws IOException { return codec.write(Map.of(FIRST, new byte[] {1, 2, 3})); }
    private static String path(UUID id) { return "documents/" + id + ".bin"; }
    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static int central(byte[] bytes) { return little(bytes).getInt(bytes.length - 6); }

    private static Map<String, byte[]> entries(byte[] bytes) throws IOException {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries, boolean compressed) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var item : entries.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                if (!compressed) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(item.getValue().length);
                    var crc = new CRC32();
                    crc.update(item.getValue());
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static void replace(byte[] bytes, byte[] before, byte[] after) {
        for (int offset = 0; offset <= bytes.length - before.length; offset++) {
            if (Arrays.equals(bytes, offset, offset + before.length, before, 0, before.length)) {
                System.arraycopy(after, 0, bytes, offset, after.length);
            }
        }
    }
}
