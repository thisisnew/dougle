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
    private final java.util.concurrent.ConcurrentMap<String, Progress> progress = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public record Progress(String stage, String file, long completed, long total, boolean cancellable) {}

    public Progress progress(String workspaceId, String id) {
        Source source = require(workspaceId, id);
        return progress.getOrDefault(id, new Progress(source.status(), "", 0, 0, false));
    }

    public void cancel(String workspaceId, String id) {
        require(workspaceId, id);
        Progress current = progress.get(id);
        if (current != null && current.cancellable()) cancelled.add(id);
    }

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
                domain.strip(), "NEW", null, null, null, null, false, 3600, null, 0, 0, null);
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
        return sync(workspaceId, id, () -> {});
    }

    public Source sync(String workspaceId, String id, Runnable refreshIndex) {
        Source source = require(workspaceId, id);
        if (!store.begin(workspaceId, id)) {
            throw new LibraryException(409, "이미 동기화 중입니다.");
        }
        return synchronizeClaimed(source, refreshIndex);
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
        return configureWorkspacePolling(workspaceId, enabled, null, expectedRevision);
    }

    public WorkspacePolling configureWorkspacePolling(String workspaceId, Boolean enabled, Integer intervalSeconds, Integer expectedRevision) {
        requireWorkspace(workspaceId);
        if (enabled == null || expectedRevision == null || expectedRevision < 0
                || (intervalSeconds != null && (intervalSeconds < 60 || intervalSeconds > 86400))) {
            throw new LibraryException(400, "자동 갱신 설정을 확인하세요.");
        }
        if (!store.configureWorkspacePolling(workspaceId, enabled, intervalSeconds, expectedRevision)) {
            throw new LibraryException(409, "설정이 변경되었습니다. 다시 확인해 주세요.");
        }
        return store.workspacePolling(workspaceId);
    }

    private Source synchronizeClaimed(Source source) {
        return synchronizeClaimed(source, () -> {});
    }

    private Source synchronizeClaimed(Source source, Runnable refreshIndex) {
        String workspaceId = source.workspaceId();
        String id = source.id();
        cancelled.remove(id);
        boolean[] publishing = {false};
        progress.put(id, new Progress("READING", "", 0, 0, true));
        try (var observation = dev.knowledgeforge.ingestion.application.ProcessingProgress.observe(step -> {
            if (!publishing[0] && cancelled.contains(id)) throw new java.util.concurrent.CancellationException();
            if ("PUBLISHING".equals(step.stage())) publishing[0] = true;
            progress.put(id, new Progress(step.stage(), step.file(), step.completed(), step.total(),
                    !publishing[0]));
        })) {
            source = require(workspaceId, id);
            var baseline = store.fileHashes(workspaceId, id);
            var collector = collector(source.kind());
            try (var previous = collector.usesPreviousSnapshot() ? store.snapshot(workspaceId, id).orElse(null) : null;
                 var snapshot = collector.usesPreviousSnapshot()
                    ? collectWithRetry(collector, source.location(), previous)
                    : collectWithRetry(collector, source.location(), null)) {
            Map<String, Analysis> analyses = new HashMap<>();
            var failures = new java.util.HashMap<>(snapshot.failures());
            var accepted = new java.util.ArrayList<>(snapshot.files());
            var plan = SnapshotPlanner.plan(snapshot, baseline);
            int completed = 0;
            for (var file : plan.changed()) {
                dev.knowledgeforge.ingestion.application.ProcessingProgress.report("EXTRACTING", file.path(), completed, plan.changed().size());
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Synchronization interrupted");
                }
                Analysis analysis = store.checkpoint(id, file.path(), file.hash()).orElse(null);
                if (analysis == null) {
                    try (var input = file.open()) {
                        analysis = analyzer.analyzeStream(file.path(), input);
                    }
                    if (analysis != null) store.checkpoint(id, file.path(), file.hash(), analysis);
                }
                if (analysis == null || "ERROR".equals(analysis.status())
                        || "UNSUPPORTED".equals(analysis.status()) && baseline.containsKey(file.path())) {
                    failures.put(file.path(), analysis == null || analysis.error() == null
                            ? "파일을 읽지 못했습니다. 손상·암호 설정을 확인하세요." : analysis.error());
                    accepted.remove(file);
                    completed++;
                    continue;
                }
                if (!store.persistentCheckpoints()) analyses.put(file.path(), analysis);
                completed++;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Synchronization interrupted");
            }
            dev.knowledgeforge.ingestion.application.ProcessingProgress.report("PUBLISHING", "", completed, plan.changed().size());
            store.publish(source, new dev.knowledgeforge.sources.domain.CollectedSnapshot(
                    snapshot.revision(), accepted, snapshot.validators(), failures), store.persistentCheckpoints()
                    ? new java.util.AbstractMap<>() {
                        public Analysis get(Object path) {
                            return store.checkpoint(id, (String) path, plan.hashes().get(path)).orElse(null);
                        }
                        public java.util.Set<Map.Entry<String, Analysis>> entrySet() { throw new UnsupportedOperationException(); }
                    } : analyses);
            refreshIndex.run();
            }
        } catch (Exception exception) {
            LOG.warn("Source {} synchronization failed", source.id(), exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String detail = exception instanceof java.util.concurrent.CancellationException ? "동기화를 중단했습니다. 다시 누르면 완료된 분석부터 이어갑니다."
                    : exception instanceof LibraryException ? exception.getMessage()
                    : "수집에 실패했습니다. 위치·접근 권한·네트워크·크기 제한을 확인하세요. ("
                    + exception.getClass().getSimpleName() + ")";
            store.fail(id, detail);
        } finally {
            progress.remove(id);
            cancelled.remove(id);
        }
        return require(workspaceId, id);
    }

    public void delete(String workspaceId, String id) {
        require(workspaceId, id);
        store.delete(workspaceId, id);
    }

    private dev.knowledgeforge.sources.domain.CollectedSnapshot collectWithRetry(Collector collector, String location,
            dev.knowledgeforge.sources.domain.CollectedSnapshot previous) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return collector.usesPreviousSnapshot() ? collector.collect(location, previous) : collector.collect(location);
            } catch (Exception exception) {
                boolean transientFailure = exception instanceof java.net.SocketTimeoutException
                        || exception instanceof java.net.ConnectException
                        || exception instanceof LibraryException failure && (failure.status() == 429
                        || failure.status() == 503 || failure.status() == 504
                        || failure.getMessage().matches(".*HTTP 오류: (429|502|503|504).*"));
                if (!transientFailure || attempt >= 2) throw exception;
                dev.knowledgeforge.ingestion.application.ProcessingProgress.report("RETRYING", "", attempt + 1, 2);
                Thread.sleep(500L << attempt);
            }
        }
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
