package dev.knowledgeforge.sources.application;

import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.sources.domain.Source;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SourceService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SourceService.class);
    private final SourceStore store;
    private final LibraryStore library;
    private final List<Collector> collectors;
    private final DocumentAnalyzer analyzer;

    public SourceService(SourceStore store, LibraryStore library, List<Collector> collectors, DocumentAnalyzer analyzer) {
        this.store = store;
        this.library = library;
        this.collectors = collectors;
        this.analyzer = analyzer;
    }

    public List<Source> list(String workspaceId) {
        requireWorkspace(workspaceId);
        return store.list(workspaceId);
    }

    public Source create(String workspaceId, String name, String kind, String location, String domain) {
        requireWorkspace(workspaceId);
        if (name == null || name.isBlank() || name.length() > 120 || name.matches(".*[\\\\/].*")
                || name.codePoints().anyMatch(Character::isISOControl) || List.of(".", "..").contains(name.strip())
                || domain == null || domain.length() > 80 || domain.codePoints().anyMatch(Character::isISOControl)) {
            throw new LibraryException(400, "이름(1~120자)과 도메인(0~80자)을 확인하세요.");
        }
        if (location == null || location.isBlank() || location.length() > 2048) {
            throw new LibraryException(400, "수집 위치를 입력하세요.");
        }
        collector(kind).validate(location.strip());
        Source source = new Source(UUID.randomUUID().toString(), workspaceId, name.strip(), kind, location.strip(),
                domain.strip(), "NEW", null, null, null, null, false, 3600);
        store.create(source);
        return source;
    }

    public Source sync(String workspaceId, String id) {
        Source source = require(workspaceId, id);
        if (!store.begin(workspaceId, id)) {
            throw new LibraryException(409, "이미 동기화 중입니다.");
        }
        try {
            var snapshot = collector(source.kind()).collect(source.location());
            Map<String, Analysis> analyses = new HashMap<>();
            var changedFiles = snapshot.revision().equals(source.revision()) && source.rootEntryId() != null
                    ? List.<dev.knowledgeforge.sources.domain.CollectedSnapshot.File>of() : snapshot.files();
            for (var file : changedFiles) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Synchronization interrupted");
                }
                analyses.put(file.path(), analyzer.analyze(file.path(), file.content()));
            }
            store.publish(source, snapshot, analyses);
        } catch (Exception exception) {
            LOG.warn("Source {} synchronization failed", source.id(), exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String detail = exception instanceof LibraryException ? exception.getMessage()
                    : "수집에 실패했습니다. 위치·접근 권한·네트워크·크기 제한을 확인하세요. ("
                    + exception.getClass().getSimpleName() + ")";
            store.fail(id, detail);
        }
        return require(workspaceId, id);
    }

    public void delete(String workspaceId, String id) {
        require(workspaceId, id);
        store.delete(workspaceId, id);
    }

    private Source require(String workspaceId, String id) {
        return store.find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "Source를 찾을 수 없습니다."));
    }

    private void requireWorkspace(String workspaceId) {
        library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
    }

    private Collector collector(String kind) {
        return collectors.stream().filter(candidate -> candidate.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new LibraryException(400, "지원하지 않는 Source 종류입니다."));
    }
}
