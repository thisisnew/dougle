package dev.knowledgeforge.plugins.adapter;

import java.util.Collection;
import java.util.regex.Pattern;

public final class DatadogLogRedactor {
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)([\\\"']?(?:password|passwd|secret|token|api[_-]?key|application[_-]?key|authorization|cookie|set-cookie)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern HEADER = Pattern.compile("(?im)\\b(?:authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]*");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?(?:-----END [^-]*PRIVATE KEY-----|\\z)");

    private DatadogLogRedactor() {}

    public static String redact(String text, Collection<String> knownSecrets) {
        String result = text == null ? "" : text;
        for (String secret : knownSecrets) {
            if (secret != null && !secret.isEmpty()) result = result.replace(secret, "[REDACTED]");
        }
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED_PRIVATE_KEY]");
        result = HEADER.matcher(result).replaceAll("[REDACTED_HEADER]");
        result = BEARER.matcher(result).replaceAll("[REDACTED_AUTH]");
        result = CREDENTIAL.matcher(result).replaceAll("$1[REDACTED]");
        result = URL.matcher(result).replaceAll("[REDACTED_URL]");
        result = EMAIL.matcher(result).replaceAll("[REDACTED_EMAIL]");
        return JWT.matcher(result).replaceAll("[REDACTED_TOKEN]");
    }
}
