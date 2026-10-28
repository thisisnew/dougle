package dev.dougle.search.application;

import java.util.List;

public interface SearchIndex extends AutoCloseable {
    record Document(String id, String workspaceId, String name, String text, String symbols,
                    String sourceId, String domain, String path, String kind, String cardId,
                    Integer version, List<String> domains, List<Integer> boundaries, List<String> textParts) {
        public Document(String id, String workspaceId, String name, String text, String symbols,
                        String sourceId, String domain, String path, String kind, String cardId,
                        Integer version, List<String> domains, List<Integer> boundaries) {
            this(id, workspaceId, name, text, symbols, sourceId, domain, path, kind, cardId, version, domains,
                    boundaries, List.of());
        }
        public String textHash() {
            return textParts.isEmpty() ? dev.dougle.search.domain.ContentDigest.of(text)
                    : dev.dougle.search.domain.ContentDigest.ofParts(textParts);
        }
        public Iterable<dev.dougle.search.domain.TextChunks.Chunk> chunks(int limit) {
            return textParts.isEmpty() ? new dev.dougle.search.domain.TextChunks(text, limit, boundaries)
                    : new dev.dougle.search.domain.StreamingTextChunks(textParts, limit);
        }
        public Document(String id, String workspaceId, String name, String text, String symbols,
                        String sourceId, String domain, String path, String kind, String cardId,
                        Integer version, List<String> domains) {
            this(id, workspaceId, name, text, symbols, sourceId, domain, path, kind, cardId, version, domains, List.of());
        }
        public Document(String id, String workspaceId, String name, String text, String symbols,
                        String sourceId, String domain, String path) {
            this(id, workspaceId, name, text, symbols, sourceId, domain, path, "ORIGINAL", null, null, List.of(domain));
        }
    }
    record Hit(String entryId, String name, String sourceId, String domain, String path, String snippet,
               float score, int startLine, int endLine, boolean bodyMatch, String kind, String cardId,
               Integer version, List<String> domains, String freshness, String textHash) {
        public Hit withFreshness(String value) {
            return new Hit(entryId, name, sourceId, domain, path, snippet, score, startLine, endLine,
                    bodyMatch, kind, cardId, version, domains, value, textHash);
        }
    }
    void reset();
    default boolean initialized() { return false; }
    default boolean questionReady() { return true; }
    void put(Document document);
    void remove(String id);
    void commit();
    default List<Hit> search(String workspaceId, String question, List<String> domains) {
        return search(workspaceId, question, domains, false);
    }
    List<Hit> search(String workspaceId, String question, List<String> domains, boolean includeKnowledge);
    List<Hit> searchQuestion(String workspaceId, String question, List<String> domains, boolean includeKnowledge);
}
