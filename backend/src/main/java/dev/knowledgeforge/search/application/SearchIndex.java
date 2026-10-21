package dev.knowledgeforge.search.application;

import java.util.List;

public interface SearchIndex extends AutoCloseable {
    record Document(String id, String workspaceId, String name, String text, String symbols,
                    String sourceId, String domain, String path, String kind, String cardId,
                    Integer version, List<String> domains) {
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
    void put(Document document);
    void remove(String id);
    void commit();
    default List<Hit> search(String workspaceId, String question, List<String> domains) {
        return search(workspaceId, question, domains, false);
    }
    List<Hit> search(String workspaceId, String question, List<String> domains, boolean includeKnowledge);
}
