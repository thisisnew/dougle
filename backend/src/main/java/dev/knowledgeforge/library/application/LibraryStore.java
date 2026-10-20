package dev.knowledgeforge.library.application;

import dev.knowledgeforge.library.domain.Entry;
import dev.knowledgeforge.library.domain.Workspace;
import java.util.List;
import java.util.Optional;

public interface LibraryStore {
    List<Workspace> workspaces();
    Optional<Workspace> workspace(String id);
    void createWorkspace(Workspace workspace);
    void deleteWorkspace(String id);
    String activeWorkspace();
    void activate(String id);
    List<Entry> entries(String workspaceId, String area, String parentId);
    Optional<Entry> entry(String workspaceId, String id);
    void createEntry(Entry entry, byte[] content);
    byte[] content(String id);
    void deleteEntry(String workspaceId, String id);
    boolean managed(String id);
}
