package dev.dougle.security.application;

import dev.dougle.library.application.LibraryException;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class CredentialContentGuard {
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)\\b(?:password|passwd|secret|token|api[_-]?key|application[_-]?key|access[_-]?token|refresh[_-]?token|authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*(\"[^\"\\r\\n]+\"|'[^'\\r\\n]+'|[^\\s,;]+)");
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]{16,}"),
            Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            Pattern.compile("\\b(?:xox[baprs]-|xapp-)[A-Za-z0-9_-]{8,}"),
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("(?i)https?://[^\\s/@:]+:[^\\s/@]+@"));

    private CredentialContentGuard() {}

    public static boolean containsCredential(String text) {
        if (text == null || text.isEmpty()) return false;
        String inspected = text.replace("\\\"", "\"");
        for (var pattern : PATTERNS) if (pattern.matcher(inspected).find()) return true;
        var assignments = ASSIGNMENT.matcher(inspected);
        while (assignments.find()) {
            String value = assignments.group(1).replace("\"", "").replace("'", "");
            if (credentialValue(value)) return true;
        }
        return false;
    }

    private static boolean credentialValue(String value) {
        if (value.length() < 16 || value.startsWith("[REDACTED") || value.startsWith("${")
                || value.startsWith("<") || value.startsWith("process.env.") || value.startsWith("System.getenv(")
                || value.matches("(?i)(?:example|placeholder|changeme|your[_-]).*")) return false;
        return value.chars().anyMatch(Character::isLetter) && value.chars().anyMatch(Character::isDigit);
    }

    public static void requireSafe(String text) {
        if (containsCredential(text)) throw new LibraryException(409,
                "알려진 인증정보 패턴이 포함되어 작업을 차단했습니다. 원본은 변경하지 않았습니다. 민감정보를 제외한 새 자료·질문으로 다시 시도하세요.");
    }

    public static void requireSafeTree(JsonNode node) {
        if (node == null) return;
        if (node.isString()) requireSafe(node.asString());
        else if (node.isContainer()) for (var child : node) requireSafeTree(child);
    }
}
