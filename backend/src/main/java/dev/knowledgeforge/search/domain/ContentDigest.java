package dev.knowledgeforge.search.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ContentDigest {
    private ContentDigest() {}

    public static String of(String text) { return of(text.getBytes(StandardCharsets.UTF_8)); }

    public static String of(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
