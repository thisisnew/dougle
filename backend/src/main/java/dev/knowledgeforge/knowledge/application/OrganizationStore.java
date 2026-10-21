package dev.knowledgeforge.knowledge.application;

import dev.knowledgeforge.knowledge.domain.Organization.*;

public interface OrganizationStore {
    Catalog catalog(String workspaceId);
    void create(String workspaceId, Folder folder);
    boolean update(String workspaceId, Folder folder, int expectedRevision);
    boolean deleteEmpty(String workspaceId, String id);
    boolean place(String workspaceId, String cardId, String folderId, int expectedRevision);
}
