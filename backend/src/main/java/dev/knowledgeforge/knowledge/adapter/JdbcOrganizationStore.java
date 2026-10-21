package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.OrganizationStore;
import dev.knowledgeforge.knowledge.domain.Organization.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationStore implements OrganizationStore {
    private final JdbcTemplate jdbc;
    public JdbcOrganizationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Catalog catalog(String workspaceId) {
        return new Catalog(jdbc.query("SELECT * FROM knowledge_folder WHERE workspace_id=? ORDER BY name,id",
                (row, index) -> new Folder(row.getString("id"), row.getString("parent_id"), row.getString("name")), workspaceId),
                jdbc.query("SELECT p.* FROM knowledge_placement p JOIN knowledge_card c ON c.id=p.card_id WHERE c.workspace_id=? ORDER BY p.card_id",
                        (row, index) -> new Placement(row.getString("card_id"), row.getString("folder_id"), row.getInt("revision")), workspaceId));
    }

    public void create(String workspaceId, Folder folder) {
        jdbc.update("INSERT INTO knowledge_folder(id,workspace_id,parent_id,name) VALUES(?,?,?,?)", folder.id(), workspaceId, folder.parentId(), folder.name());
    }

    public boolean deleteEmpty(String workspaceId, String id) {
        return jdbc.update("""
                DELETE FROM knowledge_folder WHERE workspace_id=? AND id=?
                AND NOT EXISTS(SELECT 1 FROM knowledge_folder child WHERE child.parent_id=knowledge_folder.id)
                AND NOT EXISTS(SELECT 1 FROM knowledge_placement WHERE folder_id=knowledge_folder.id)
                """, workspaceId, id) == 1;
    }

    public boolean place(String workspaceId, String cardId, String folderId, int expectedRevision) {
        return jdbc.update("""
                INSERT INTO knowledge_placement(card_id,folder_id,revision)
                SELECT id,?,1 FROM knowledge_card WHERE workspace_id=? AND id=?
                AND COALESCE((SELECT revision FROM knowledge_placement WHERE card_id=?),0)=?
                ON CONFLICT(card_id) DO UPDATE SET folder_id=excluded.folder_id,revision=knowledge_placement.revision+1
                """, folderId, workspaceId, cardId, cardId, expectedRevision) == 1;
    }
}
