package dev.knowledgeforge.search.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EvidenceBundleService {
    private final SearchService search;
    private final EvidenceBundleWriter writer;
    private final EvidenceBundleStore store;
    private final LibraryStore library;

    public EvidenceBundleService(SearchService search, EvidenceBundleWriter writer, EvidenceBundleStore store, LibraryStore library) {
        this.search = search;
        this.writer = writer;
        this.store = store;
        this.library = library;
    }

    public EvidenceBundle create(String workspaceId, String question, List<String> domains, boolean includeKnowledge) {
        if (domains.size() > 20 || domains.stream().anyMatch(value -> value == null || value.length() > 80 || value.codePoints().anyMatch(Character::isISOControl))) {
            throw new LibraryException(400, "도메인은 최대 20개, 각각 80자 이하로 입력하세요.");
        }
        var hits = search.search(workspaceId, question, domains, includeKnowledge);
        return writer.capture(workspaceId, question.strip(), domains, includeKnowledge, hits);
    }

    public List<EvidenceBundle.Summary> list(String workspaceId) {
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        return store.list(workspaceId);
    }

    public EvidenceBundle get(String workspaceId, String id) {
        return store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "근거 묶음을 찾을 수 없습니다."));
    }

    public void delete(String workspaceId, String id) {
        get(workspaceId, id);
        store.delete(workspaceId, id);
    }
}
