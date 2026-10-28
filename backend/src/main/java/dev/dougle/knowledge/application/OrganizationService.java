package dev.dougle.knowledge.application;

import dev.dougle.knowledge.domain.Organization.*;
import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrganizationService {
    private final OrganizationStore store;
    private final LibraryStore library;
    private final KnowledgeStore knowledge;

    public OrganizationService(OrganizationStore store, LibraryStore library, KnowledgeStore knowledge) {
        this.store = store;
        this.library = library;
        this.knowledge = knowledge;
    }

    public Catalog catalog(String workspaceId) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
        return store.catalog(workspaceId);
    }

    public Folder create(String workspaceId, String parentId, String name) {
        Catalog catalog = catalog(workspaceId);
        validateName(name);
        String ancestor = parentId;
        var visited = new HashSet<String>();
        while (ancestor != null) {
            if (!visited.add(ancestor) || visited.size() >= 16) throw new LibraryException(400, "지식 폴더는 최대 16단계까지 만들 수 있습니다.");
            ancestor = requireFolder(catalog, ancestor).parentId();
        }
        Folder folder = new Folder(UUID.randomUUID().toString(), parentId, name.strip(), 1);
        store.create(workspaceId, folder);
        return folder;
    }

    public Folder update(String workspaceId, String id, String parentId, String name, int expectedRevision) {
        Catalog catalog = catalog(workspaceId);
        Folder current = requireFolder(catalog, id);
        validateName(name);
        if (current.revision() != expectedRevision) throw new LibraryException(409, "폴더가 변경되었습니다. 새로고침 후 변경 화면을 다시 여세요.");
        if (parentId != null) requireFolder(catalog, parentId);
        var parents = new java.util.HashMap<String, String>();
        catalog.folders().forEach(folder -> parents.put(folder.id(), folder.parentId()));
        parents.put(id, parentId);
        for (Folder folder : catalog.folders()) {
            var visited = new HashSet<String>();
            String ancestor = folder.id();
            while (ancestor != null) {
                if (!visited.add(ancestor)) throw new LibraryException(400, "자신이나 하위 폴더로 이동할 수 없습니다.");
                if (visited.size() > 16) throw new LibraryException(400, "하위 폴더를 포함해 최대 16단계까지 이동할 수 있습니다.");
                ancestor = parents.get(ancestor);
            }
        }
        Folder updated = new Folder(id, parentId, name.strip(), expectedRevision + 1);
        if (!store.update(workspaceId, updated, expectedRevision)) throw new LibraryException(409, "폴더가 변경되었습니다. 다시 확인하세요.");
        return updated;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120
                || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)
                || List.of(".", "..").contains(name.strip())) {
            throw new LibraryException(400, "폴더 이름은 경로 구분자 없이 1~120자로 입력하세요.");
        }
    }

    public void delete(String workspaceId, String id) {
        requireFolder(catalog(workspaceId), id);
        if (!store.deleteEmpty(workspaceId, id)) throw new LibraryException(409, "빈 지식 폴더만 삭제할 수 있습니다. 카드와 하위 폴더를 먼저 정리하세요.");
    }

    public Catalog place(String workspaceId, String cardId, String folderId, int expectedRevision) {
        Catalog catalog = catalog(workspaceId);
        knowledge.card(workspaceId, cardId).orElseThrow(() -> new LibraryException(404, "지식을 찾을 수 없습니다."));
        if (folderId != null) requireFolder(catalog, folderId);
        if (expectedRevision < 0 || !store.place(workspaceId, cardId, folderId, expectedRevision)) {
            throw new LibraryException(409, "카드 위치가 변경되었습니다. 폴더 새로고침 후 다시 이동하세요.");
        }
        return store.catalog(workspaceId);
    }

    private Folder requireFolder(Catalog catalog, String id) {
        return catalog.folders().stream().filter(folder -> folder.id().equals(id)).findFirst()
                .orElseThrow(() -> new LibraryException(404, "지식 폴더를 찾을 수 없습니다."));
    }
}
