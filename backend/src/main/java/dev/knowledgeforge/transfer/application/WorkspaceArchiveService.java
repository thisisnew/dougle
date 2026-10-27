package dev.knowledgeforge.transfer.application;

import dev.knowledgeforge.conversation.application.AnswerJobStore;
import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.conversation.domain.AnswerJob;
import dev.knowledgeforge.ingestion.application.AnalysisStore;
import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Entry;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import dev.knowledgeforge.search.application.ContextPrompt;
import dev.knowledgeforge.security.application.CredentialContentGuard;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.transfer.application.WorkspaceArchiveData.*;
import dev.knowledgeforge.transfer.domain.TransferArchiveCodec;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkspaceArchiveService {
    public static final UUID CATALOG_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public record Preview(String previewId, String digest, String expiresAt, String workspaceName, int materials,
                          int threads, int turns, int answers, String limitation) {}
    public record Apply(String requestId, String previewId, String digest) {}
    public record Result(String workspaceId, List<String> threadIds, int materials, String indexStatus) {}
    private record Snapshot(WorkspaceArchiveData catalog, Map<UUID, byte[]> bytes) {}
    private static final class Stage {
        final Snapshot snapshot;
        final Map<UUID, Analysis> analyses;
        final Preview preview;
        Stage(Snapshot snapshot, Map<UUID, Analysis> analyses, Preview preview) {
            this.snapshot = snapshot; this.analyses = analyses; this.preview = preview;
        }
    }
    private final LibraryStore library;
    private final SourceStore sources;
    private final ChatThreadService threads;
    private final AnswerJobStore jobs;
    private final WorkspaceArchivePersistence persistence;
    private final WorkspaceArchiveReceipts receipts;
    private final DocumentAnalyzer analyzer;
    private final AnalysisStore analyses;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final TransferArchiveCodec codec = new TransferArchiveCodec();
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final Map<String, Stage> stages = new HashMap<>();

    public WorkspaceArchiveService(LibraryStore library, SourceStore sources, ChatThreadService threads, AnswerJobStore jobs,
                                   WorkspaceArchivePersistence persistence, WorkspaceArchiveReceipts receipts, DocumentAnalyzer analyzer, AnalysisStore analyses,
                                   PlatformTransactionManager transactions, Clock clock) {
        this.library = library; this.sources = sources; this.threads = threads; this.jobs = jobs;
        this.persistence = persistence; this.receipts = receipts; this.analyzer = analyzer; this.analyses = analyses;
        this.transaction = new TransactionTemplate(transactions); this.clock = clock;
    }

    public byte[] exportArchive(String workspaceId) {
        var snapshot = transaction.execute(status -> capture(workspaceId));
        validate(snapshot);
        var documents = new LinkedHashMap<>(snapshot.bytes());
        var catalog = json.valueToTree(snapshot.catalog());
        if (snapshot.catalog().version() == 1) {
            for (var thread : catalog.path("threads")) for (var turn : thread.path("turns")) {
                ((tools.jackson.databind.node.ObjectNode) turn).remove("extractive");
            }
        }
        documents.put(CATALOG_KEY, json.writeValueAsBytes(catalog));
        try { return codec.write(documents); }
        catch (IOException exception) { throw invalid("자료가 아카이브 크기 제한을 넘습니다. 자료를 나누어 이전하세요."); }
    }

    private Snapshot capture(String workspaceId) {
        var workspace = library.workspace(workspaceId).orElseThrow(() -> new LibraryException(404, "Workspace를 찾을 수 없습니다."));
        persistence.requireSupportedHistory(workspaceId);
        if (sources.list(workspaceId).stream().anyMatch(source -> "SYNCING".equals(source.status()))) throw conflict("자료 수집이 끝난 뒤 내보내세요.");
        if (jobs.list(workspaceId).stream().anyMatch(job -> !"SUCCEEDED".equals(job.status()))) throw conflict("완료되지 않은 답변 작업은 이전할 수 없습니다. 작업 이력을 정리한 뒤 다시 시도하세요.");
        var materials = new ArrayList<Material>();
        var bytes = new LinkedHashMap<UUID, byte[]>();
        var entries = new HashMap<String, UUID>();
        collect(workspaceId, null, null, materials, bytes, entries, new HashSet<>());
        var exportedThreads = new ArrayList<ThreadData>();
        int turnCount = 0;
        for (var summary : threads.list(workspaceId)) {
            var detail = threads.get(workspaceId, summary.id());
            var turns = new ArrayList<Turn>();
            var prior = new HashMap<String, EvidenceBundle>();
            var turnKeys = new HashMap<String, UUID>();
            var exported = new HashMap<String, EvidenceBundle>();
            for (var bundle : detail.bundles()) {
                if (++turnCount > 100) throw invalid("대화 질문은 최대 100개까지 이전할 수 있습니다.");
                var payload = bundle.payload();
                if (payload.includeKnowledge() || !payload.domains().isEmpty()) throw conflict("지식 문서 또는 분류 범위에 연결된 근거는 이 형식으로 이전할 수 없습니다.");
                var evidence = new ArrayList<Evidence>();
                for (var item : payload.items()) {
                    if (!"ORIGINAL".equals(item.kind()) || !item.supports().isEmpty() || !entries.containsKey(item.entryId())) {
                        throw conflict("첨부·지식 문서·삭제된 자료의 근거는 이전할 수 없습니다.");
                    }
                    var material = library.entry(workspaceId, item.entryId()).orElseThrow();
                    if (!material.name().equals(item.title())) throw conflict("근거의 자료 이름이 변경되었습니다.");
                    evidence.add(new Evidence(entries.get(item.entryId()), item.contentHash(), item.textHash(), item.startLine(), item.endLine(), item.quote()));
                }
                var history = new ArrayList<History>();
                if (payload.chatContext() != null) {
                    ContextPrompt.validate(workspaceId, payload.question(), payload.chatContext(), prior::get);
                    for (var old : payload.chatContext().history()) {
                        var previous = prior.get(old.bundleId());
                        if (previous == null || !previous.digest().equals(old.bundleDigest())) throw conflict("대화 근거 연결을 검증하지 못했습니다.");
                        history.add(new History(turnKeys.get(old.bundleId()), old.question(), old.answer()));
                    }
                }
                var answers = detail.jobs().stream().filter(job -> job.bundleId().equals(bundle.id())).map(job ->
                        new Answer(job.provider(), job.requestedModel(), job.answer(), job.createdAt(), job.updatedAt())).toList();
                UUID key = UUID.randomUUID();
                var turn = new Turn(key, bundle.createdAt(), payload.question(), payload.chatContext() == null ? payload.question() : payload.chatContext().retrievalQuery(),
                        List.copyOf(evidence), List.copyOf(history), answers,
                        rebind(payload.chatContext() == null ? null : payload.chatContext().extractive(), exported::get));
                turns.add(turn);
                exported.put(bundle.id(), archiveBundle(turn, summary.id(), turns.size() > 1 ? turns.get(turns.size() - 2).key().toString() : null));
                prior.put(bundle.id(), bundle); turnKeys.put(bundle.id(), key);
            }
            exportedThreads.add(new ThreadData(List.copyOf(turns)));
        }
        boolean extractive = exportedThreads.stream().flatMap(thread -> thread.turns().stream()).anyMatch(turn -> turn.extractive() != null);
        return new Snapshot(new WorkspaceArchiveData(extractive ? 2 : 1, workspace.name(), List.copyOf(materials), List.copyOf(exportedThreads)), Map.copyOf(bytes));
    }

    private void collect(String workspaceId, String parentId, UUID parentKey, List<Material> materials, Map<UUID, byte[]> bytes,
                         Map<String, UUID> entries, Set<String> visited) {
        for (var entry : library.entries(workspaceId, "ORIGINAL", parentId)) {
            if (materials.size() >= 63 || !visited.add(entry.id())) throw invalid("자료는 폴더를 포함하여 최대 63개이며 순환 구조를 허용하지 않습니다.");
            UUID key = UUID.randomUUID();
            entries.put(entry.id(), key);
            materials.add(new Material(key, parentKey, entry.name(), entry.kind(), entry.createdAt()));
            if (entry.kind().equals("FILE")) {
                if (entry.size() > TransferArchiveCodec.MAX_DOCUMENT_BYTES) throw invalid("각 파일은 1 MiB 이하여야 합니다.");
                bytes.put(key, library.content(entry.id()));
                if (bytes.values().stream().mapToLong(content -> content.length).sum() > TransferArchiveCodec.MAX_TOTAL_BYTES) throw invalid("자료 전체 크기는 8 MiB 이하여야 합니다.");
            } else if (entry.kind().equals("FOLDER")) collect(workspaceId, entry.id(), key, materials, bytes, entries, visited);
            else throw invalid("지원하지 않는 자료 종류입니다.");
        }
    }

    public Preview preview(byte[] archive, String workspaceName) {
        validName(workspaceName, 20);
        CredentialContentGuard.requireSafe(workspaceName);
        requireNewName(workspaceName);
        Snapshot snapshot;
        try {
            var bytes = new HashMap<>(codec.read(archive).documents());
            var catalogBytes = bytes.remove(CATALOG_KEY);
            if (catalogBytes == null) throw invalid("Workspace 목록이 없는 아카이브입니다.");
            var catalog = json.readTree(catalogBytes);
            if (catalog.path("version").asInt() == 1) {
                for (var thread : catalog.path("threads")) for (var turn : thread.path("turns")) {
                    if (!turn.has("extractive")) ((tools.jackson.databind.node.ObjectNode) turn).putNull("extractive");
                }
            }
            snapshot = new Snapshot(json.treeToValue(catalog, WorkspaceArchiveData.class), Map.copyOf(bytes));
        } catch (IOException | RuntimeException exception) { throw invalid("아카이브 형식·버전·크기·체크섬을 확인하세요."); }
        var prepared = validate(snapshot);
        String digest = ContentDigest.of(ContentDigest.of(archive) + "\n" + workspaceName);
        long now = clock.millis();
        var preview = new Preview(UUID.randomUUID().toString(), digest, Instant.ofEpochMilli(now + 600_000).toString(), workspaceName,
                snapshot.catalog().materials().size(), snapshot.catalog().threads().size(), snapshot.catalog().threads().stream().mapToInt(thread -> thread.turns().size()).sum(),
                snapshot.catalog().threads().stream().flatMap(thread -> thread.turns().stream()).mapToInt(turn -> turn.answers().size()).sum(),
                "새 Workspace에 자료 복사본과 대화를 저장합니다. 원격 연결·인증·승인·실행 작업은 이전하지 않습니다. 원본 근거와 대화 연결의 ID·digest는 새로 계산합니다. 검색 색인은 로컬 분석에서 다시 구성하며 검색 엔진 준비가 필요합니다. 알려진 인증정보 패턴은 차단하며 원본을 자동 수정하지 않습니다. 모든 비밀정보·개인정보 탐지를 보장하지 않습니다.");
        synchronized (stages) {
            stages.values().removeIf(stage -> Instant.parse(stage.preview.expiresAt()).toEpochMilli() <= now);
            if (stages.size() >= 4) throw new LibraryException(429, "열린 아카이브 미리보기가 너무 많습니다. 10분 뒤 다시 시도하세요.");
            stages.put(preview.previewId(), new Stage(snapshot, prepared, preview));
        }
        return preview;
    }

    public Result apply(Apply request) {
        if (request == null || !uuid(request.requestId()) || !uuid(request.previewId()) || request.digest() == null
                || !request.digest().matches("[a-f0-9]{64}")) throw invalid("요청 ID와 미리보기 확인 값이 필요합니다.");
        Stage stage;
        synchronized (stages) { stage = stages.get(request.previewId()); }
        return transaction.execute(status -> {
            if (!receipts.reserve(request.requestId(), request.digest(), request.previewId())) throw conflict("이미 사용한 미리보기입니다. 원래 요청 ID로 결과를 확인하세요.");
            var receipt = receipts.get(request.requestId());
            if (!receipt.digest().equals(request.digest()) || !receipt.previewId().equals(request.previewId())) throw conflict("같은 요청 ID를 다른 가져오기에 사용할 수 없습니다.");
            if (receipt.resultJson() != null) return json.readValue(receipt.resultJson(), Result.class);
            if (stage == null || !stage.preview.digest().equals(request.digest()) || !Instant.parse(stage.preview.expiresAt()).isAfter(clock.instant())) {
                throw conflict("미리보기가 변경되었거나 만료되었습니다. 다시 업로드하세요.");
            }
            var result = restore(stage);
            receipts.complete(request.requestId(), json.writeValueAsString(result));
            return result;
        });
    }

    private Result restore(Stage stage) {
        if (!Instant.parse(stage.preview.expiresAt()).isAfter(clock.instant())) throw conflict("미리보기가 만료되었습니다. 다시 업로드하세요.");
        requireSafeContents(stage.snapshot);
        stage.analyses.values().forEach(analysis -> CredentialContentGuard.requireSafe(analysis.text()));
        requireNewName(stage.preview.workspaceName());
        String workspaceId = UUID.randomUUID().toString();
        library.createWorkspace(new Workspace(workspaceId, stage.preview.workspaceName(), clock.instant().toString()));
        if (!sources.configureWorkspacePolling(workspaceId, false, 3600, 0)) throw conflict("갱신 비활성화 설정을 저장하지 못했습니다.");
        var ids = new HashMap<UUID, String>();
        for (var material : stage.snapshot.catalog().materials()) {
            String id = UUID.randomUUID().toString();
            byte[] content = material.kind().equals("FILE") ? stage.snapshot.bytes().get(material.key()) : new byte[0];
            library.createEntry(new Entry(id, workspaceId, material.parentKey() == null ? null : ids.get(material.parentKey()), material.name(), material.kind(), "ORIGINAL", content.length, material.createdAt()), content);
            ids.put(material.key(), id);
            if (material.kind().equals("FILE")) analyses.save(id, stage.analyses.get(material.key()));
        }
        var threadIds = new ArrayList<String>();
        for (var thread : stage.snapshot.catalog().threads()) {
            String threadId = UUID.randomUUID().toString();
            threadIds.add(threadId);
            var imported = new HashMap<UUID, EvidenceBundle>();
            String parent = null;
            for (var turn : thread.turns()) {
                var items = new ArrayList<EvidenceBundle.Item>();
                for (var evidence : turn.evidence()) {
                    var material = stage.snapshot.catalog().materials().stream().filter(candidate -> candidate.key().equals(evidence.materialKey())).findFirst().orElseThrow();
                    items.add(new EvidenceBundle.Item("R" + (items.size() + 1), "ORIGINAL", ids.get(material.key()), null, null, material.name(),
                            null, material.name(), null, evidence.contentHash(), evidence.textHash(), evidence.startLine(), evidence.endLine(), evidence.quote(), List.of()));
                }
                var history = turn.history().stream().map(old -> {
                    var previous = imported.get(old.turnKey());
                    return new EvidenceBundle.ChatTurn(previous.id(), previous.digest(), old.question(), old.answer());
                }).toList();
                var context = new EvidenceBundle.ChatContext(threadId, parent, UUID.randomUUID().toString(), turn.retrievalQuery(), history,
                        rebind(turn.extractive(), key -> imported.get(UUID.fromString(key))));
                ContextPrompt.validate(workspaceId, turn.question(), context, key -> imported.values().stream().filter(previous -> previous.id().equals(key)).findFirst().orElseThrow());
                int usedCharacters = items.stream().mapToInt(item -> item.quote().length()).sum();
                int maxCharacters = ContextPrompt.evidenceCharacters(context);
                if (context.extractive() != null && (usedCharacters > maxCharacters
                        || !ContextPrompt.withinRequestBudgets(dev.knowledgeforge.search.application.EvidenceAnswerPreview.renderPrompt(turn.question(), items, context)))) {
                    throw invalid("복원한 대화 문맥과 근거가 요청 예산을 초과합니다.");
                }
                var payload = new EvidenceBundle.Payload(turn.question(), List.of(), false, items.isEmpty() ? "NO_EVIDENCE" : "READY",
                        usedCharacters, maxCharacters, List.copyOf(items), List.of(), context);
                var bundle = persistence.saveBundle(workspaceId, turn.createdAt(), payload);
                imported.put(turn.key(), bundle); parent = bundle.id();
                for (var answer : turn.answers()) {
                    jobs.create(new AnswerJob(UUID.randomUUID().toString(), workspaceId, bundle.id(), UUID.randomUUID().toString(), answer.provider(), answer.model(),
                            ContentDigest.of(bundle.digest() + "\n" + answer.text()), "SUCCEEDED", answer.text(), null, answer.createdAt(), answer.updatedAt()));
                }
            }
        }
        return new Result(workspaceId, List.copyOf(threadIds), ids.size(), "QUEUED_LOCAL_REBUILD");
    }

    private Map<UUID, Analysis> validate(Snapshot snapshot) {
        try { return validateContents(snapshot); }
        catch (LibraryException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid("아카이브의 필수 필드와 참조를 확인하세요."); }
    }

    private Map<UUID, Analysis> validateContents(Snapshot snapshot) {
        requireSafeContents(snapshot);
        var catalog = snapshot.catalog();
        if (catalog.version() != 1 && catalog.version() != 2) throw invalid("지원하지 않는 Workspace 아카이브 버전입니다.");
        validName(catalog.workspaceName(), 120);
        if (catalog.materials().size() > 63 || catalog.threads().size() > 100) throw invalid("자료·대화 개수 제한을 넘었습니다.");
        var materials = new HashMap<UUID, Material>();
        var siblings = new HashSet<String>();
        var prepared = new HashMap<UUID, Analysis>();
        var fileKeys = new HashSet<UUID>();
        for (var material : catalog.materials()) {
            validName(material.name(), 120); Instant.parse(material.createdAt());
            if (material.key() == null || material.key().equals(CATALOG_KEY) || materials.containsKey(material.key())
                    || !Set.of("FILE", "FOLDER").contains(material.kind())) throw invalid("자료 식별자·종류가 잘못되었습니다.");
            if (material.parentKey() != null && (!materials.containsKey(material.parentKey()) || !materials.get(material.parentKey()).kind().equals("FOLDER"))) throw invalid("상위 폴더는 자식보다 먼저 있어야 하며 순환·외부 참조를 허용하지 않습니다.");
            if (!siblings.add(material.parentKey() + "/" + material.name().toLowerCase(java.util.Locale.ROOT))) throw invalid("같은 폴더에 중복 이름이 있습니다.");
            materials.put(material.key(), material);
            if (material.kind().equals("FILE")) {
                var bytes = snapshot.bytes().get(material.key());
                if (bytes == null || bytes.length > TransferArchiveCodec.MAX_DOCUMENT_BYTES) throw invalid("자료 내용이 없거나 너무 큽니다.");
                fileKeys.add(material.key());
                var analysis = analyzer.analyze(material.name(), bytes);
                if (analysis.textTruncated()) {
                    analysis.close();
                    throw invalid("이 아카이브 형식은 큰 추출문을 지원하지 않습니다. 원본 자료를 별도로 등록하세요.");
                }
                CredentialContentGuard.requireSafe(analysis.text());
                prepared.put(material.key(), analysis);
            }
        }
        if (!fileKeys.equals(snapshot.bytes().keySet())) throw invalid("목록에 없는 자료 또는 누락된 자료가 있습니다.");
        var turnKeys = new HashSet<UUID>();
        int answerCount = 0;
        for (var thread : catalog.threads()) {
            if (thread.turns().isEmpty()) throw invalid("빈 대화는 허용하지 않습니다.");
            var previous = new HashMap<UUID, Turn>();
            var previousBundles = new HashMap<String, EvidenceBundle>();
            String parent = null;
            String threadId = UUID.randomUUID().toString();
            for (var turn : thread.turns()) {
                if (turn.key() == null || !turnKeys.add(turn.key()) || turnKeys.size() > 100) throw invalid("중복 질문 또는 질문 개수 제한을 확인하세요.");
                text(turn.question(), 256); text(turn.retrievalQuery(), 256); Instant.parse(turn.createdAt());
                if (turn.evidence().size() > 5 || turn.history().size() > 5) throw invalid("질문 근거·이력 개수 제한을 넘었습니다.");
                if (catalog.version() == 1 && turn.extractive() != null) throw invalid("이전 아카이브 버전에 새 대화 문맥을 포함할 수 없습니다.");
                int used = 0;
                for (var evidence : turn.evidence()) {
                    var analysis = prepared.get(evidence.materialKey());
                    if (analysis == null || !Set.of("READY", "PARTIAL").contains(analysis.status())
                            || !ContentDigest.of(snapshot.bytes().get(evidence.materialKey())).equals(evidence.contentHash())
                            || !ContentDigest.of(analysis.text()).equals(evidence.textHash())) throw invalid("근거의 자료·추출문 해시가 현재 자료와 다릅니다. 안전하게 재연결할 수 없습니다.");
                    text(evidence.quote(), 12000); used += evidence.quote().length();
                    if (evidence.startLine() < 1 || evidence.endLine() < evidence.startLine()) throw invalid("인용 행 범위를 확인하세요.");
                    int lineStart = 0;
                    for (int line = 1; line < evidence.startLine(); line++) {
                        int next = analysis.text().indexOf('\n', lineStart);
                        if (next < 0) throw invalid("인용 행이 자료 범위를 벗어났습니다.");
                        lineStart = next + 1;
                    }
                    int lineEnd = analysis.text().indexOf('\n', lineStart);
                    if (lineEnd < 0) lineEnd = analysis.text().length();
                    int offset = analysis.text().indexOf(evidence.quote(), lineStart);
                    int last = evidence.startLine() + (int) evidence.quote().chars().limit(Math.max(0, evidence.quote().length() - 1)).filter(character -> character == '\n').count();
                    boolean matched = offset >= lineStart && offset < lineEnd && last == evidence.endLine();
                    if (!matched || used > 12000) throw invalid("인용문·행 범위가 자료와 일치하지 않습니다.");
                }
                var seenHistory = new HashSet<UUID>();
                for (var history : turn.history()) {
                    var prior = previous.get(history.turnKey());
                    if (prior == null || !seenHistory.add(history.turnKey()) || !prior.question().equals(history.question())
                            || history.answer() != null && (history.answer().length() > 800 || prior.answers().stream().noneMatch(answer ->
                                    Objects.equals(clip(answer.text(), turn.extractive() == null ? 800 : turn.extractive().answerCharacterLimit()), history.answer())))) {
                        throw invalid("대화 이력이 이전 질문·완료 답변과 일치하지 않습니다.");
                    }
                }
                for (var answer : turn.answers()) {
                    if (++answerCount > 100 || !Set.of("CODEX", "CLAUDE").contains(answer.provider())) throw invalid("답변 제공자 또는 개수 제한을 확인하세요.");
                    if (!"".equals(answer.model())) text(answer.model(), 120);
                    text(answer.text(), 200_000); Instant.parse(answer.createdAt()); Instant.parse(answer.updatedAt());
                }
                var synthetic = archiveBundle(turn, threadId, parent);
                var history = turn.history().stream().map(old -> {
                    var source = previousBundles.get(old.turnKey().toString());
                    return new EvidenceBundle.ChatTurn(source.id(), source.digest(), old.question(), old.answer());
                }).toList();
                var context = new EvidenceBundle.ChatContext(threadId, parent, "archive", turn.retrievalQuery(), history, turn.extractive());
                try { ContextPrompt.validate("archive", turn.question(), context, previousBundles::get); }
                catch (RuntimeException invalidContext) { throw invalid("대화 문맥의 순서·해시·예산·생략 정보를 검증하지 못했습니다."); }
                previousBundles.put(synthetic.id(), synthetic);
                parent = synthetic.id();
                previous.put(turn.key(), turn);
            }
        }
        return Map.copyOf(prepared);
    }

    private void requireSafeContents(Snapshot snapshot) {
        CredentialContentGuard.requireSafeTree(json.valueToTree(snapshot.catalog()));
        snapshot.bytes().values().forEach(bytes -> CredentialContentGuard.requireSafe(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String clip(String text, int limit) {
        if (limit == 0) return null;
        if (limit < 0 || limit > 800) throw invalid("답변 문맥 길이 제한이 잘못되었습니다.");
        if (text.length() <= limit) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
    }

    private EvidenceBundle archiveBundle(Turn turn, String threadId, String parent) {
        var context = new EvidenceBundle.ChatContext(threadId, parent, "archive", turn.retrievalQuery(), List.of(), turn.extractive());
        var payload = new EvidenceBundle.Payload(turn.question(), List.of(), false, "NO_EVIDENCE", 0, 12000, List.of(), List.of(), context);
        return new EvidenceBundle(turn.key().toString(), "archive", turn.createdAt(), ContentDigest.of(json.writeValueAsString(turn)), payload);
    }

    private EvidenceBundle.ExtractiveContext rebind(EvidenceBundle.ExtractiveContext context, java.util.function.Function<String, EvidenceBundle> resolve) {
        if (context == null) return null;
        var questions = context.questions().stream().map(question -> {
            var source = Objects.requireNonNull(resolve.apply(question.bundleId()));
            return new EvidenceBundle.UserQuestion(source.id(), source.digest(), question.question(), question.legacyUnclassified());
        }).toList();
        var truncated = context.truncatedAnswerBundleIds().stream().map(key -> Objects.requireNonNull(resolve.apply(key)).id()).toList();
        return new EvidenceBundle.ExtractiveContext(context.version(), context.turnKind(), context.totalQuestions(), context.omittedQuestions(),
                context.maxSerializedCharacters(), context.answerCharacterLimit(), context.omittedAnswers(), truncated, questions, context.retrievalQueries());
    }
    private void requireNewName(String name) {
        if (library.workspaces().stream().anyMatch(workspace -> workspace.name().equalsIgnoreCase(name))) throw conflict("같은 이름의 Workspace가 있습니다. 새 이름을 입력하세요.");
    }
    private static void validName(String name, int max) {
        text(name, max);
        if (!name.equals(name.strip()) || name.contains("/") || name.contains("\\") || Set.of(".", "..").contains(name) || name.codePoints().anyMatch(Character::isISOControl)) throw invalid("경로 구분자 없는 이름을 입력하세요.");
    }
    private static void text(String value, int max) { if (value == null || value.isBlank() || value.length() > max) throw invalid("필수 문자열 또는 길이 제한을 확인하세요."); }
    private static boolean uuid(String value) { return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private static LibraryException invalid(String message) { return new LibraryException(400, message); }
    private static LibraryException conflict(String message) { return new LibraryException(409, message); }
}
