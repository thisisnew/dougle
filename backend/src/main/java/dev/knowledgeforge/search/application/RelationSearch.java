package dev.knowledgeforge.search.application;

import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationSearch {
    public record Result(List<Item> items, boolean truncated) {}
    public record Item(RelationCatalog.Related target, String freshness) {}
    private final RelationCatalog catalog;
    private final LibraryStore library;
    private final KnowledgeStore knowledge;

    public RelationSearch(RelationCatalog catalog, LibraryStore library, KnowledgeStore knowledge) {
        this.catalog = catalog;
        this.library = library;
        this.knowledge = knowledge;
    }

    @Transactional(readOnly = true)
    public Result find(String workspaceId, String kind, String id, Integer version, List<String> domains) {
        if (kind == null || !List.of("ORIGINAL", "KNOWLEDGE").contains(kind) || id == null || id.isBlank() || id.length() > 128
                || ("KNOWLEDGE".equals(kind) && (version == null || version < 1))
                || domains.size() > 20 || domains.stream().anyMatch(domain -> domain == null || domain.length() > 80)) {
            throw new LibraryException(400, "관계 검색 대상과 도메인을 확인하세요.");
        }
        if (library.workspace(workspaceId).isEmpty() || !catalog.exists(workspaceId, kind, id, version)) {
            throw new LibraryException(404, "현재 검색 가능한 원본 또는 게시본을 찾을 수 없습니다. 다시 검색하세요.");
        }
        var candidates = catalog.related(workspaceId, kind, id, version, domains);
        var items = candidates.stream().limit(30).map(target -> {
            if (!"KNOWLEDGE".equals(target.kind())) return new Item(target, "LOCAL_COPY");
            var stored = knowledge.version(workspaceId, target.id(), target.version()).orElseThrow();
            String freshness = stored.evidence().isEmpty() || stored.evidence().stream().anyMatch(item -> "MISSING".equals(item.freshness()))
                    ? "MISSING" : stored.evidence().stream().anyMatch(item -> "STALE".equals(item.freshness())) ? "STALE" : "CURRENT";
            return new Item(target, freshness);
        }).toList();
        return new Result(items, candidates.size() > 30);
    }
}
