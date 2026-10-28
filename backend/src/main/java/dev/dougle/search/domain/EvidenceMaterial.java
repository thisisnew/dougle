package dev.dougle.search.domain;

import java.util.List;

public record EvidenceMaterial(String entryId, String sourceId, String revision, String path, String name,
                               String analysisStatus, String contentHash, String text, List<String> textParts) {
    public String textHash() {
        return textParts.isEmpty() ? ContentDigest.of(text) : ContentDigest.ofParts(textParts);
    }

    public EvidenceWindow window(String snippet, int startLine, int endLine, int budget, boolean inspected) {
        int limit = inspected ? budget : Math.min(6000, budget);
        if (snippet == null || snippet.isBlank() || snippet.length() > limit || startLine < 1
                || endLine != startLine + snippet.chars().limit(Math.max(0, snippet.length() - 1))
                        .filter(value -> value == '\n').count()) return null;
        int[] prefix = new int[snippet.length()];
        for (int index = 1, matched = 0; index < snippet.length(); index++) {
            while (matched > 0 && snippet.charAt(index) != snippet.charAt(matched)) matched = prefix[matched - 1];
            if (snippet.charAt(index) == snippet.charAt(matched)) matched++;
            prefix[index] = matched;
        }
        int line = 1;
        int matched = 0;
        int snippetNewlines = (int) snippet.chars().filter(value -> value == '\n').count();
        for (String part : textParts.isEmpty() ? List.of(text) : textParts) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int index = 0; index < part.length(); index++) {
                char character = part.charAt(index);
                if (line > endLine) return null;
                if (line >= startLine) {
                    while (matched > 0 && character != snippet.charAt(matched)) matched = prefix[matched - 1];
                    if (character == snippet.charAt(matched)) matched++;
                    if (matched == snippet.length()) {
                        int firstLine = line - snippetNewlines + (character == '\n' ? 1 : 0);
                        if (firstLine == startLine) {
                            if (textParts.isEmpty() && !inspected) return EvidenceWindow.around(text, snippet, startLine, endLine, budget);
                            return new EvidenceWindow(snippet, startLine, endLine);
                        }
                        matched = prefix[matched - 1];
                    }
                }
                if (character == '\n') line++;
            }
        }
        return null;
    }
}
