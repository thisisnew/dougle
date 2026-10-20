package dev.knowledgeforge.search.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private final SearchCatalog catalog;
    private final SearchIndex index;
    private final LibraryStore library;

    public SearchService(SearchCatalog catalog, SearchIndex index, LibraryStore library) {
        this.catalog = catalog;
        this.index = index;
        this.library = library;
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
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        if (query == null || query.isBlank() || query.length() > 256 || domains.size() > 20) {
            throw new LibraryException(400, "검색어는 1~256자, 도메인은 최대 20개입니다.");
        }
        return index.search(workspaceId, query.strip(), domains).stream()
                .filter(hit -> catalog.exists(workspaceId, hit.entryId())).toList();
    }
}
