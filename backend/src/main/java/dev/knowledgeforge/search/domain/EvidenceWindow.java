package dev.knowledgeforge.search.domain;

public record EvidenceWindow(String text, int startLine, int endLine) {
    public static EvidenceWindow around(String original, String snippet, int startLine, int endLine, int budget) {
        int limit = Math.min(6000, budget);
        if (original.length() <= limit) return new EvidenceWindow(original, original.isEmpty() ? 0 : 1, lines(original));
        int offset = 0;
        for (int line = 1; line < startLine && offset < original.length(); line++) {
            int newline = original.indexOf('\n', offset);
            if (newline < 0) return new EvidenceWindow(snippet, startLine, endLine);
            offset = newline + 1;
        }
        int match = original.indexOf(snippet, offset);
        if (match < 0 || snippet.length() > limit) return new EvidenceWindow(snippet, startLine, endLine);
        int padding = Math.max(0, Math.min(2400, limit) - snippet.length());
        int first = Math.max(0, match - padding / 2);
        int last = Math.min(original.length(), match + snippet.length() + padding - (match - first));
        if (first > 0) {
            int boundary = original.indexOf('\n', first);
            if (boundary >= 0 && boundary < match) first = boundary + 1;
            else if (Character.isLowSurrogate(original.charAt(first))) first++;
        }
        if (last < original.length()) {
            int boundary = original.lastIndexOf('\n', last - 1);
            if (boundary >= match + snippet.length()) last = boundary + 1;
            else if (Character.isLowSurrogate(original.charAt(last))) last--;
        }
        String text = original.substring(first, last);
        int line = 1 + (int) original.substring(0, first).chars().filter(value -> value == '\n').count();
        return new EvidenceWindow(text, line, line + lines(text) - 1);
    }

    private static int lines(String text) {
        return text.isEmpty() ? 0 : 1 + (int) text.chars().limit(Math.max(0, text.length() - 1)).filter(value -> value == '\n').count();
    }
}
