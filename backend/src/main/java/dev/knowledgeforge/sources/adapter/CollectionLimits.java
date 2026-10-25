package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.domain.CollectedSnapshot;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class CollectionLimits {
    public static final int MAX_FILE = 10 * 1024 * 1024;
    public static final long MAX_DOCUMENT = CollectedSnapshot.MAX_FILE_BYTES;
    public static final long MAX_SNAPSHOT = 8L * 1024 * 1024 * 1024;
    public static final int MAX_SNAPSHOT_FILES = 20_000;
    private static final Set<String> EXCLUDED = Set.of(".git", ".ssh", ".aws", ".env",
            "node_modules", "target", ".data", ".tools", ".m2", "dist", ".DS_Store",
            ".idea", ".gradle", ".classpath", ".project", "__pycache__");

    private CollectionLimits() {}

    public static boolean excluded(String name) {
        return EXCLUDED.contains(name) || name.startsWith(".env.")
                || name.endsWith(".class") || name.endsWith(".pyc");
    }

    public static void path(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length > 16) throw new LibraryException(413, "폴더 깊이는 16단계 이하여야 합니다.");
        for (String part : parts) {
            if (part.isBlank() || part.length() > 120 || part.contains("\\")
                    || part.equals(".") || part.equals("..") || part.codePoints().anyMatch(Character::isISOControl)) {
                throw new LibraryException(400, "지원하지 않는 파일 경로입니다.");
            }
        }
    }

    public static byte[] read(InputStream input) throws java.io.IOException {
        byte[] content = input.readNBytes(MAX_FILE + 1);
        if (content.length > MAX_FILE) throw new LibraryException(413, "파일당 수집 한도는 10 MiB입니다.");
        return content;
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static CollectedSnapshot snapshot(List<CollectedSnapshot.File> files) {
        if (files.size() > MAX_SNAPSHOT_FILES || files.stream().mapToLong(CollectedSnapshot.File::size).sum() > MAX_SNAPSHOT) {
            throw new LibraryException(413, "폴더를 나눠 연결하세요. 한 자료당 20,000개 파일·8 GiB까지 처리합니다.");
        }
        var sorted = files.stream().sorted(Comparator.comparing(CollectedSnapshot.File::path)).toList();
        StringBuilder manifest = new StringBuilder();
        for (var file : sorted) {
            path(file.path());
            if (file.size() > MAX_DOCUMENT) throw new LibraryException(413, "256 MiB가 넘는 파일은 나눠 등록하세요.");
            manifest.append(file.path()).append('\0').append(file.hash()).append('\n');
        }
        return new CollectedSnapshot(hash(manifest.toString().getBytes(StandardCharsets.UTF_8)), sorted);
    }
}
