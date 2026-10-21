package dev.knowledgeforge.search.application;

import java.util.List;

public interface SearchIndex extends AutoCloseable {
    record Document(String id, String workspaceId, String name, String text, String symbols,
                    String sourceId, String domain, String path) {}
    record Hit(String entryId, String name, String sourceId, String domain, String path, String snippet,
               float score, int startLine, int endLine, boolean bodyMatch) {}
    void reset();
    void put(Document document);
    void remove(String id);
    void commit();
    List<Hit> search(String workspaceId, String question, List<String> domains);
}
