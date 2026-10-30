package dev.dougle.search.application;

import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.knowledge.application.KnowledgeStore;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private final java.util.concurrent.locks.ReentrantLock indexLock = new java.util.concurrent.locks.ReentrantLock();
    private final SearchCatalog catalog;
    private final SearchIndex index;
    private final LibraryStore library;
    private final KnowledgeStore knowledge;

    public SearchService(SearchCatalog catalog, SearchIndex index, LibraryStore library, KnowledgeStore knowledge) {
        this.catalog = catalog;
        this.index = index;
        this.library = library;
        this.knowledge = knowledge;
    }

    @PostConstruct
    public void initialize() {
        if (!index.questionReady()) return;
        initializeIndex();
    }

    public void initializeIndex() {
        indexLock.lock();
        try {
            if (index.initialized()) refresh();
            else rebuild();
        } finally { indexLock.unlock(); }
    }

    public void rebuild() {
        indexLock.lock();
        try {
            index.reset();
            catalog.each(index::put);
            index.commit();
            refresh();
        } finally { indexLock.unlock(); }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void refresh() {
        indexLock.lock();
        try {
            if (!index.questionReady()) return;
            var changes = catalog.changes();
            while (!changes.isEmpty()) {
                for (var change : changes) {
                    catalog.withDocument(change.entryId(), index::put, () -> index.remove(change.entryId()));
                }
                index.commit();
                catalog.acknowledge(changes.getLast().sequence());
                changes = catalog.changes();
            }
        } finally { indexLock.unlock(); }
    }

    public List<SearchIndex.Hit> search(String workspaceId, String query, List<String> domains) {
        return search(workspaceId, query, domains, false);
    }

    public List<SearchIndex.Hit> search(String workspaceId, String query, List<String> domains, boolean includeKnowledge) {
        lockForSearch();
        try { return find(workspaceId, query, domains, includeKnowledge, false); }
        finally { indexLock.unlock(); }
    }

    public List<SearchIndex.Hit> searchQuestion(String workspaceId, String query, List<String> domains, boolean includeKnowledge) {
        if (!index.questionReady()) throw new LibraryException(503, "자료 검색을 준비하고 있습니다. 잠시 후 다시 질문해 주세요.");
        lockForSearch();
        try {
            if (!index.questionReady()) throw new LibraryException(503, "자료 검색을 준비하고 있습니다. 잠시 후 다시 질문해 주세요.");
            return find(workspaceId, query, domains, includeKnowledge, true);
        } finally { indexLock.unlock(); }
    }

    public List<SearchIndex.Hit> searchQuestionCandidates(String workspaceId, String query) {
        if (workspaceId == null || workspaceId.isBlank() || query == null || query.isBlank() || query.length() > 256) {
            throw new LibraryException(400, "로컬 검색 범위 또는 질문이 올바르지 않습니다.");
        }
        if (!index.questionReady()) throw new LibraryException(503, "자료 검색을 준비하고 있습니다. 잠시 후 다시 질문해 주세요.");
        return index.searchQuestion(workspaceId, query.strip(), List.of(), false);
    }

    public void requireQuestionReady() {
        lockForSearch();
        try { if (!index.questionReady()) throw preparing(); }
        finally { indexLock.unlock(); }
    }

    private void lockForSearch() {
        try {
            if (!indexLock.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) throw preparing();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LibraryException(409, "자료 검색을 중단했습니다.");
        }
    }

    private LibraryException preparing() {
        return new LibraryException(503, "자료를 검색용으로 준비하고 있습니다. 동기화가 끝난 뒤 다시 질문해 주세요.");
    }

    private List<SearchIndex.Hit> find(String workspaceId, String query, List<String> domains, boolean includeKnowledge, boolean naturalQuestion) {
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        if (query == null || query.isBlank() || query.length() > 256 || domains.size() > 20) {
            throw new LibraryException(400, "검색어는 1~256자, 도메인은 최대 20개입니다.");
        }
        var hits = naturalQuestion ? index.searchQuestion(workspaceId, query.strip(), domains, includeKnowledge)
                : index.search(workspaceId, query.strip(), domains, includeKnowledge);
        return hits.stream()
                .filter(hit -> catalog.exists(workspaceId, hit))
                .map(hit -> {
                    if (!"KNOWLEDGE".equals(hit.kind())) return hit;
                    return knowledge.version(workspaceId, hit.cardId(), hit.version()).map(version -> {
                        var evidence = version.evidence();
                        String freshness = evidence.isEmpty() || evidence.stream().anyMatch(item -> "MISSING".equals(item.freshness()))
                                ? "MISSING" : evidence.stream().anyMatch(item -> "STALE".equals(item.freshness())) ? "STALE" : "CURRENT";
                        return hit.withFreshness(freshness);
                    }).orElse(null);
                }).filter(java.util.Objects::nonNull).toList();
    }
}
