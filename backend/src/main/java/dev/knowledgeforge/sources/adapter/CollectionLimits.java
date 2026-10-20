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
    public static final int MAX_TOTAL = 50 * 1024 * 1024;
    public static final int MAX_FILES = 500;
    private static final Set<String> EXCLUDED = Set.of(".git", ".ssh", ".aws", ".env",
            "node_modules", "target", ".data", ".tools", ".m2", "dist", ".DS_Store");

    private CollectionLimits() {}

    public static boolean excluded(String name) {
        return EXCLUDED.contains(name) || name.startsWith(".env.");
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
        if (files.size() > MAX_FILES || files.stream().mapToLong(file -> file.content().length).sum() > MAX_TOTAL) {
            throw new LibraryException(413, "Source당 최대 500개 파일, 총 50 MiB를 수집할 수 있습니다.");
        }
        var sorted = files.stream().sorted(Comparator.comparing(CollectedSnapshot.File::path)).toList();
        StringBuilder manifest = new StringBuilder();
        for (var file : sorted) {
            path(file.path());
            if (file.content().length > MAX_FILE) throw new LibraryException(413, "파일당 수집 한도는 10 MiB입니다.");
            manifest.append(file.path()).append('\0').append(hash(file.content())).append('\n');
        }
        return new CollectedSnapshot(hash(manifest.toString().getBytes(StandardCharsets.UTF_8)), sorted);
    }
}
