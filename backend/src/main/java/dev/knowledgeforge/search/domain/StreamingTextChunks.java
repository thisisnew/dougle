package dev.knowledgeforge.search.domain;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class StreamingTextChunks implements Iterable<TextChunks.Chunk> {
    private final Iterable<String> parts;
    private final int limit;

    public StreamingTextChunks(Iterable<String> parts, int limit) {
        if (limit < 256) throw new IllegalArgumentException("Chunk size too small");
        this.parts = parts;
        this.limit = limit;
    }

    public Iterator<TextChunks.Chunk> iterator() {
        return new Iterator<>() {
            private final Iterator<String> source = parts.iterator();
            private final StringBuilder buffer = new StringBuilder();
            private int offset;
            private int line = 1;

            public boolean hasNext() {
                while (buffer.length() <= limit && source.hasNext()) buffer.append(source.next());
                return !buffer.isEmpty();
            }

            public TextChunks.Chunk next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int end = Math.min(limit, buffer.length());
                if (end < buffer.length()) {
                    String window = buffer.substring(0, end);
                    int paragraph = window.lastIndexOf("\n\n");
                    int newline = window.lastIndexOf('\n');
                    if (paragraph > limit / 2) end = paragraph + 2;
                    else if (newline > limit / 2) end = newline + 1;
                    if (Character.isLowSurrogate(buffer.charAt(end))) end--;
                }
                var result = new TextChunks.Chunk(buffer.substring(0, end), line, offset);
                int next = end;
                if (end < buffer.length()) {
                    int overlap = Math.max(1, end - 240);
                    int boundary = buffer.substring(overlap, end).indexOf('\n');
                    if (boundary >= 0 && overlap + boundary + 1 < end) next = overlap + boundary + 1;
                }
                for (int index = 0; index < next; index++) if (buffer.charAt(index) == '\n') line++;
                offset = Math.addExact(offset, next);
                buffer.delete(0, next);
                return result;
            }
        };
    }
}
