package dev.knowledgeforge.search.domain;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class TextChunks implements Iterable<TextChunks.Chunk> {
    public record Chunk(String text, int startLine, int offset) {}
    private final String text;
    private final int limit;
    private final java.util.NavigableSet<Integer> boundaries = new java.util.TreeSet<>();

    public TextChunks(String text, int limit) {
        this(text, limit, java.util.List.of());
    }

    public TextChunks(String text, int limit, java.util.List<Integer> startLines) {
        if (limit < 256) throw new IllegalArgumentException("Chunk size too small");
        this.text = text;
        this.limit = limit;
        if (!startLines.isEmpty()) {
            var lines = new java.util.HashSet<>(startLines);
            int line = 1;
            for (int offset = 0; offset < text.length(); offset++) {
                if (offset == 0 || text.charAt(offset - 1) == '\n') {
                    if (lines.contains(line)) boundaries.add(offset);
                }
                if (text.charAt(offset) == '\n') line++;
            }
        }
    }

    public Iterator<Chunk> iterator() {
        return new Iterator<>() {
            private int offset;
            private int line = 1;
            private boolean emptyEmitted;

            public boolean hasNext() { return offset < text.length() || text.isEmpty() && !emptyEmitted; }

            public Chunk next() {
                if (!hasNext()) throw new NoSuchElementException();
                emptyEmitted = true;
                int end = Math.min(text.length(), offset + limit);
                boolean structuralSplit = false;
                if (end < text.length()) {
                    Integer structure = boundaries.floor(end);
                    String window = text.substring(offset, end);
                    int paragraph = offset + window.lastIndexOf("\n\n");
                    int newline = offset + window.lastIndexOf('\n');
                    if (structure != null && structure > offset + limit / 4) {
                        end = structure;
                        structuralSplit = true;
                    }
                    else if (paragraph > offset + limit / 2) end = paragraph + 2;
                    else if (newline > offset + limit / 2) end = newline + 1;
                    if (Character.isLowSurrogate(text.charAt(end))) end--;
                }
                Chunk result = new Chunk(text.substring(offset, end), text.isEmpty() ? 0 : line, offset);
                int next = end;
                if (end < text.length() && !structuralSplit) {
                    int overlap = Math.max(offset + 1, end - 240);
                    int boundary = text.substring(overlap, end).indexOf('\n');
                    if (boundary >= 0 && overlap + boundary + 1 < end) next = overlap + boundary + 1;
                }
                for (int index = offset; index < next; index++) if (text.charAt(index) == '\n') line++;
                offset = next;
                return result;
            }
        };
    }
}
