package dev.knowledgeforge.transfer.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.sources.application.SourceService;
import dev.knowledgeforge.sources.application.SourceStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TeamSettingsImportService {
    public record Mapping(int sourceIndex, String location) {}
    public record PlanRequest(TeamSettingsService.Settings settings, String workspaceName, List<Mapping> mappings) {}
    public record Plan(String previewId, String digest, String expiresAt, String workspaceName,
                       int sourcesToCreate, List<Integer> reconnectSourceIndices, boolean pollingEnabled, String limitation) {}
    public record ApplyRequest(String requestId, String previewId, String digest) {}
    public record Applied(String workspaceId, List<String> sourceIds, List<Integer> reconnectSourceIndices, boolean pollingEnabled) {}
    private record Pending(PlanRequest request, Plan plan, long expiresAt) {}
    private final LibraryStore library;
    private final SourceStore sources;
    private final SourceService sourceService;
    private final TeamSettingsImportReceipts receipts;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HashMap<String, Pending> pending = new HashMap<>();

    public TeamSettingsImportService(LibraryStore library, SourceStore sources, SourceService sourceService,
                                     TeamSettingsImportReceipts receipts, PlatformTransactionManager transactions, Clock clock) {
        this.library = library;
        this.sources = sources;
        this.sourceService = sourceService;
        this.receipts = receipts;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public Plan plan(PlanRequest input) {
        if (input == null) throw invalid("가져올 설정을 지정하세요.");
        TeamSettingsService.validate(input.settings());
        TeamSettingsService.validate(new TeamSettingsService.Settings(1, input.workspaceName(), input.settings().pollIntervalSeconds(), List.of()));
        if (input.workspaceName().length() > 20) throw invalid("새 Workspace 이름은 20자 이내로 입력하세요.");
        if (input.mappings() == null || input.mappings().size() > TeamSettingsService.MAX_SOURCES) throw invalid("자료 위치 매핑을 확인하세요.");
        var mappings = new HashMap<Integer, Mapping>();
        for (var mapping : input.mappings()) {
            if (mapping == null || mapping.sourceIndex() < 0 || mapping.sourceIndex() >= input.settings().sources().size()
                    || mappings.putIfAbsent(mapping.sourceIndex(), mapping) != null) throw invalid("자료 위치 번호가 잘못되었거나 중복되었습니다.");
            var descriptor = input.settings().sources().get(mapping.sourceIndex());
            if (descriptor.kind().equals("MYSQL")) throw invalid("MySQL은 가져온 위치를 사용하지 않습니다. 플러그인에서 다시 연결하세요.");
            validateLocation(descriptor.kind(), mapping.location());
        }
        var names = new java.util.HashSet<String>();
        var reconnect = new ArrayList<Integer>();
        for (int index = 0; index < input.settings().sources().size(); index++) {
            var descriptor = input.settings().sources().get(index);
            if (!names.add(descriptor.name().toLowerCase(java.util.Locale.ROOT))) throw invalid("자료 이름이 중복됩니다. 설정의 이름을 수정하세요.");
            if (descriptor.kind().equals("MYSQL")) reconnect.add(index);
            else if (!mappings.containsKey(index)) throw invalid("모든 폴더·URL·GitHub 자료의 위치를 직접 지정하세요.");
        }
        requireNewName(input.workspaceName());
        var settings = new TeamSettingsService.Settings(1, input.settings().workspaceName(), input.settings().pollIntervalSeconds(), List.copyOf(input.settings().sources()));
        var canonical = new PlanRequest(settings, input.workspaceName(), mappings.values().stream().sorted(java.util.Comparator.comparingInt(Mapping::sourceIndex)).toList());
        String previewId = UUID.randomUUID().toString();
        long expiresAt = clock.millis() + 600_000;
        var plan = new Plan(previewId, digest(canonical), java.time.Instant.ofEpochMilli(expiresAt).toString(), input.workspaceName(), mappings.size(),
                List.copyOf(reconnect), false, "새 Workspace만 만듭니다. 자동 갱신·동기화·AI는 실행하지 않습니다. MySQL 자료는 생성하지 않으며 플러그인 재연결 후 직접 추가해야 합니다. URL 접근 가능 여부와 인증은 확인하지 않습니다.");
        long now = clock.millis();
        synchronized (pending) {
            pending.values().removeIf(value -> value.expiresAt() <= now);
            if (pending.size() >= 128) throw new LibraryException(429, "미리보기가 너무 많습니다. 잠시 후 다시 시도하세요.");
            pending.put(previewId, new Pending(canonical, plan, expiresAt));
        }
        return plan;
    }

    public Applied apply(ApplyRequest input) {
        if (input == null || !uuid(input.requestId()) || !uuid(input.previewId()) || input.digest() == null || !input.digest().matches("[a-f0-9]{64}")) {
            throw invalid("요청 ID와 미리보기 확인 값을 확인하세요.");
        }
        Pending preview;
        synchronized (pending) { preview = pending.get(input.previewId()); }
        return transaction.execute(status -> {
            if (!receipts.reserve(input.requestId(), input.digest(), input.previewId())) {
                throw conflict("이미 적용한 미리보기입니다. 기존 요청 ID로 결과를 다시 확인하세요.");
            }
            var receipt = receipts.get(input.requestId());
            if (!receipt.digest().equals(input.digest()) || !receipt.previewId().equals(input.previewId())) throw conflict("같은 요청 ID를 다른 가져오기에 사용할 수 없습니다.");
            if (receipt.resultJson() != null) return json.readValue(receipt.resultJson(), Applied.class);
            if (preview == null || preview.expiresAt() <= clock.millis() || !preview.plan().digest().equals(input.digest())) {
                throw conflict("미리보기가 만료되었거나 변경되었습니다. 다시 확인하세요.");
            }
            requireNewName(preview.request().workspaceName());
            String workspaceId = UUID.randomUUID().toString();
            library.createWorkspace(new Workspace(workspaceId, preview.request().workspaceName(), clock.instant().toString()));
            if (!sources.configureWorkspacePolling(workspaceId, false, preview.request().settings().pollIntervalSeconds(), 0)) {
                throw conflict("자동 갱신 설정을 저장하지 못했습니다.");
            }
            var sourceIds = new ArrayList<String>();
            for (var mapping : preview.request().mappings()) {
                var descriptor = preview.request().settings().sources().get(mapping.sourceIndex());
                var source = sourceService.create(workspaceId, descriptor.name(), descriptor.kind(), mapping.location(), "");
                sourceIds.add(source.id());
            }
            var result = new Applied(workspaceId, List.copyOf(sourceIds), preview.plan().reconnectSourceIndices(), false);
            receipts.complete(input.requestId(), json.writeValueAsString(result));
            return result;
        });
    }

    private void requireNewName(String name) {
        if (library.workspaces().stream().anyMatch(workspace -> workspace.name().equalsIgnoreCase(name))) throw conflict("같은 이름의 Workspace가 있습니다. 새 이름을 입력하세요.");
    }

    private void validateLocation(String kind, String location) {
        if (location == null || location.isBlank() || location.length() > 2048 || !location.equals(location.strip())
                || location.codePoints().anyMatch(Character::isISOControl)) throw invalid("자료 위치를 직접 지정하세요.");
        if (kind.equals("LOCAL_FOLDER")) {
            try {
                if (!java.nio.file.Path.of(location).isAbsolute()) throw invalid("폴더는 절대 경로로 지정하세요.");
            } catch (java.nio.file.InvalidPathException exception) { throw invalid("폴더 경로를 확인하세요."); }
            return;
        }
        try {
            var uri = URI.create(location);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || location.contains("\\")) throw invalid("사용자정보·쿼리·fragment가 없는 HTTPS 주소를 입력하세요.");
        } catch (IllegalArgumentException exception) { throw invalid("HTTPS 주소를 확인하세요."); }
    }

    private String digest(PlanRequest input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private boolean uuid(String value) { return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private static LibraryException invalid(String message) { return new LibraryException(400, message); }
    private static LibraryException conflict(String message) { return new LibraryException(409, message); }
}
