package dev.dougle.search.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentDigest {
    private ContentDigest() {}

    public static String of(String text) { return of(text.getBytes(StandardCharsets.UTF_8)); }

    public static String ofParts(Iterable<String> parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static String of(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
