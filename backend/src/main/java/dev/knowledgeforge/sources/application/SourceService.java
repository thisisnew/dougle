package dev.knowledgeforge.sources.application;

import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.sources.domain.Source;
import dev.knowledgeforge.sources.domain.WorkspacePolling;
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
        validateMetadata(name, domain);
        if (location == null || location.isBlank() || location.length() > 2048) {
            throw new LibraryException(400, "수집 위치를 입력하세요.");
        }
        collector(kind).validate(location.strip());
        Source source = new Source(UUID.randomUUID().toString(), workspaceId, name.strip(), kind, location.strip(),
                domain.strip(), "NEW", null, null, null, null, false, 3600, null, 0, 0);
        store.create(source);
        return require(workspaceId, source.id());
    }

    public Source editMetadata(String workspaceId, String id, String name, String domain, Integer expectedRevision) {
        require(workspaceId, id);
        validateMetadata(name, domain);
        if (expectedRevision == null || expectedRevision < 0) {
            throw new LibraryException(400, "현재 자료 설정 버전을 지정하세요.");
        }
        if (!store.editMetadata(workspaceId, id, name.strip(), domain.strip(), expectedRevision)) {
            throw new LibraryException(409, "수집 중이거나 자료 설정이 변경되었습니다. 편집을 닫고 다시 열어 주세요.");
        }
        return require(workspaceId, id);
    }

    private void validateMetadata(String name, String domain) {
        if (name == null || name.isBlank() || name.length() > 120 || name.matches(".*[\\\\/].*")
                || name.codePoints().anyMatch(Character::isISOControl) || List.of(".", "..").contains(name.strip())
                || domain == null || domain.length() > 80 || domain.codePoints().anyMatch(Character::isISOControl)) {
            throw new LibraryException(400, "이름(1~120자)과 도메인(0~80자)을 확인하세요.");
        }
    }

    public Source sync(String workspaceId, String id) {
        Source source = require(workspaceId, id);
        if (!store.begin(workspaceId, id)) {
            throw new LibraryException(409, "이미 동기화 중입니다.");
        }
        return synchronizeClaimed(source);
    }

    public void poll(String workspaceId, String id) {
        if (store.beginPoll(workspaceId, id)) {
            store.find(workspaceId, id).ifPresent(this::synchronizeClaimed);
        }
    }

    public Source configurePolling(String workspaceId, String id, Boolean enabled, Integer intervalSeconds, Integer expectedRevision) {
        require(workspaceId, id);
        if (enabled == null || intervalSeconds == null || intervalSeconds < 60 || intervalSeconds > 86400
                || expectedRevision == null || expectedRevision < 0) {
            throw new LibraryException(400, "자동 갱신 설정과 주기(60~86400초)를 확인하세요.");
        }
        if (!store.configurePolling(workspaceId, id, enabled, intervalSeconds, expectedRevision)) {
            throw new LibraryException(409, "자동 갱신 설정이 변경되었습니다. 새로고침 후 다시 시도하세요.");
        }
        return require(workspaceId, id);
    }

    public WorkspacePolling workspacePolling(String workspaceId) {
        requireWorkspace(workspaceId);
        return store.workspacePolling(workspaceId);
    }

    public WorkspacePolling configureWorkspacePolling(String workspaceId, Boolean enabled, Integer expectedRevision) {
        requireWorkspace(workspaceId);
        if (enabled == null || expectedRevision == null || expectedRevision < 0) {
            throw new LibraryException(400, "자동 갱신 설정을 확인하세요.");
        }
        if (!store.configureWorkspacePolling(workspaceId, enabled, expectedRevision)) {
            throw new LibraryException(409, "설정이 변경되었습니다. 다시 확인해 주세요.");
        }
        return store.workspacePolling(workspaceId);
    }

    private Source synchronizeClaimed(Source source) {
        String workspaceId = source.workspaceId();
        String id = source.id();
        try {
            source = require(workspaceId, id);
            var baseline = store.fileHashes(workspaceId, id);
            var collector = collector(source.kind());
            var snapshot = collector.usesPreviousSnapshot()
                    ? collector.collect(source.location(), store.snapshot(workspaceId, id).orElse(null))
                    : collector.collect(source.location());
            Map<String, Analysis> analyses = new HashMap<>();
            var plan = SnapshotPlanner.plan(snapshot, baseline);
            for (var file : plan.changed()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Synchronization interrupted");
                }
                Analysis analysis = analyzer.analyze(file.path(), file.content());
                if (analysis == null || "ERROR".equals(analysis.status())) {
                    throw new LibraryException(422, "변경 파일 분석에 실패했습니다. 이전 스냅샷을 유지합니다.");
                }
                analyses.put(file.path(), analysis);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Synchronization interrupted");
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
