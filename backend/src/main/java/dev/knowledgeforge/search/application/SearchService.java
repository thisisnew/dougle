package dev.knowledgeforge.search.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
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
    public synchronized void rebuild() {
        index.reset();
        catalog.each(index::put);
        index.commit();
        refresh();
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public synchronized void refresh() {
        var changes = catalog.changes();
        while (!changes.isEmpty()) {
            for (var change : changes) {
                catalog.document(change.entryId()).ifPresentOrElse(index::put, () -> index.remove(change.entryId()));
            }
            index.commit();
            catalog.acknowledge(changes.getLast().sequence());
            changes = catalog.changes();
        }
    }

    public synchronized List<SearchIndex.Hit> search(String workspaceId, String query, List<String> domains) {
        return search(workspaceId, query, domains, false);
    }

    public synchronized List<SearchIndex.Hit> search(String workspaceId, String query, List<String> domains, boolean includeKnowledge) {
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        if (query == null || query.isBlank() || query.length() > 256 || domains.size() > 20) {
            throw new LibraryException(400, "검색어는 1~256자, 도메인은 최대 20개입니다.");
        }
        return index.search(workspaceId, query.strip(), domains, includeKnowledge).stream()
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
