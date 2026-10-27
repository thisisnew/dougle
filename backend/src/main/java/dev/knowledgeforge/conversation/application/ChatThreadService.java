package dev.knowledgeforge.conversation.application;

import dev.knowledgeforge.conversation.domain.AnswerJob;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.EvidenceBundleValidator;
import dev.knowledgeforge.search.application.EvidenceBundleWriter;
import dev.knowledgeforge.search.application.ContextPrompt;
import dev.knowledgeforge.search.application.ConversationRetrieval;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import dev.knowledgeforge.search.domain.EvidenceBundle.ChatContext;
import dev.knowledgeforge.search.domain.EvidenceBundle.ChatTurn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Transactional
public class ChatThreadService {
    public record Create(String requestId, String threadId, String parentBundleId, String question, List<String> attachmentIds) {}
    public record Summary(String id, String question, String createdAt, int turns, List<String> questions) {}
    public record Detail(String id, List<EvidenceBundle> bundles, List<AnswerJob> jobs) {}
    private final EvidenceBundleService bundles;
    private final EvidenceBundleWriter writer;
    private final EvidenceBundleValidator validator;
    private final ConversationRetrieval retrieval;
    private final AnswerJobStore jobs;
    private final TransactionTemplate transaction;
    private final java.util.Map<RequestKey, Boolean> inFlight = new java.util.HashMap<>();
    private record RequestKey(String workspaceId, String requestId) {}
    private record Prepared(EvidenceBundle existing, ChatContext context, List<String> queries, boolean missingPrior) {}

    public ChatThreadService(EvidenceBundleService bundles, EvidenceBundleWriter writer, EvidenceBundleValidator validator,
                             ConversationRetrieval retrieval, AnswerJobStore jobs, PlatformTransactionManager manager) {
        this.bundles = bundles; this.writer = writer; this.validator = validator; this.retrieval = retrieval; this.jobs = jobs;
        transaction = new TransactionTemplate(manager);
    }

    private String threadId(EvidenceBundle bundle) {
        return bundle.payload().chatContext() == null ? bundle.id() : bundle.payload().chatContext().threadId();
    }

    private List<EvidenceBundle> all(String workspaceId) {
        return bundles.list(workspaceId).stream().map(summary -> bundles.get(workspaceId, summary.id())).toList();
    }

    private List<EvidenceBundle> ordered(List<EvidenceBundle> turns) {
        var ids = turns.stream().map(EvidenceBundle::id).collect(java.util.stream.Collectors.toSet());
        var remaining = new ArrayList<>(turns);
        var result = new ArrayList<EvidenceBundle>();
        String parentId = null;
        while (!remaining.isEmpty()) {
            String expected = parentId;
            var candidates = remaining.stream().filter(bundle -> {
                var context = bundle.payload().chatContext();
                String parent = context == null ? null : context.parentBundleId();
                return expected == null ? parent == null || !ids.contains(parent) : expected.equals(parent);
            }).toList();
            if (candidates.size() != 1) throw new LibraryException(409, "대화 순서를 확인하지 못했습니다.");
            var next = candidates.getFirst();
            result.add(next); remaining.remove(next); parentId = next.id();
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(String workspaceId) {
        var grouped = new LinkedHashMap<String, List<EvidenceBundle>>();
        for (var bundle : all(workspaceId)) {
            String id = threadId(bundle);
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(bundle);
        }
        return grouped.entrySet().stream().map(group -> {
            var turns = ordered(group.getValue());
            return new Summary(group.getKey(), turns.getFirst().payload().question(), turns.getLast().createdAt(), turns.size(),
                    turns.stream().map(bundle -> bundle.payload().question()).toList());
        }).sorted(Comparator.comparing((Summary summary) -> java.time.Instant.parse(summary.createdAt())).reversed().thenComparing(Summary::id)).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(String workspaceId, String id) {
        return find(workspaceId, id).orElseThrow(() -> new LibraryException(404, "대화를 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Detail> find(String workspaceId, String id) {
        var turns = ordered(all(workspaceId).stream().filter(bundle -> threadId(bundle).equals(id)).toList());
        if (turns.isEmpty()) return java.util.Optional.empty();
        var ids = turns.stream().map(EvidenceBundle::id).toList();
        return java.util.Optional.of(new Detail(id, turns, jobs.list(workspaceId).stream().filter(job -> ids.contains(job.bundleId())).toList()));
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public EvidenceBundle create(String workspaceId, Create request) {
        return createInternal(workspaceId, request, null);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public EvidenceBundle createWithRetrieval(String workspaceId, Create request, String retrievalQuery) {
        if (retrievalQuery == null || retrievalQuery.isBlank() || retrievalQuery.length() > 256) {
            throw new LibraryException(400, "추가 검색어는 1~256자여야 합니다.");
        }
        return createInternal(workspaceId, request, retrievalQuery.strip());
    }

    private EvidenceBundle createInternal(String workspaceId, Create request, String retrievalOverride) {
        return createInternal(workspaceId, request, retrievalOverride, null);
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public EvidenceBundle createFromSnapshot(String workspaceId, Create request,
                                             dev.knowledgeforge.search.application.LocalSearchSnapshot snapshot) {
        if (snapshot == null || !workspaceId.equals(snapshot.workspaceId())
                || !java.time.Instant.now().isBefore(snapshot.expiresAt())
                || request == null || request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
            throw new LibraryException(409, "검색 스냅샷이 없거나 만료되었거나 범위가 다릅니다.");
        }
        return createInternal(workspaceId, request, snapshot.query(), snapshot);
    }

    private EvidenceBundle createInternal(String workspaceId, Create request, String retrievalOverride,
                                          dev.knowledgeforge.search.application.LocalSearchSnapshot snapshot) {
        if (request == null || request.question() == null || request.question().isBlank() || request.question().length() > 256
                || request.requestId() == null || !request.requestId().matches("[a-zA-Z0-9-]{1,64}")) {
            throw new LibraryException(400, "질문은 1~256자이며 요청 식별자가 필요합니다.");
        }
        var frozen = new Create(request.requestId(), request.threadId(), request.parentBundleId(), request.question(),
                request.attachmentIds() == null ? List.of() : List.copyOf(request.attachmentIds()));
        var key = new RequestKey(workspaceId, request.requestId());
        synchronized (inFlight) {
            if (inFlight.size() >= 32 || inFlight.putIfAbsent(key, true) != null) {
                throw new LibraryException(409, "로컬 검색이 진행 중입니다. 같은 요청의 접수 상태를 다시 확인하세요.");
            }
        }
        try {
            var prepared = transaction.execute(status -> prepare(workspaceId, frozen, retrievalOverride, null, snapshot != null));
            if (prepared.existing() != null) {
                if (snapshot != null) exactSnapshot(prepared.existing(), snapshot);
                return prepared.existing();
            }
            var attempt = new ConversationRetrieval.Attempt(java.time.Duration.ofSeconds(10));
            var result = snapshot == null ? retrieval.retrieve(workspaceId, prepared.queries(), attempt)
                    : new ConversationRetrieval.Result(ConversationRetrieval.Status.OK,
                            snapshot.entries().stream().map(dev.knowledgeforge.search.application.LocalSearchSnapshot.Entry::hit).toList());
            attempt.checkpoint();
            if (result.status() != ConversationRetrieval.Status.OK && result.status() != ConversationRetrieval.Status.EMPTY) {
                int code = switch (result.status()) {
                    case TIMEOUT -> 504;
                    case CANCELLED -> 409;
                    case DENIED -> 429;
                    default -> 503;
                };
                throw new LibraryException(code, "LOCAL_SEARCH_" + result.status() + ": 로컬 검색을 완료하지 못했습니다. 답변은 실행하지 않았습니다.");
            }
            return transaction.execute(status -> {
                attempt.checkpoint();
                var current = prepare(workspaceId, frozen, retrievalOverride, prepared.context().threadId(), snapshot != null);
                if (current.existing() != null) {
                    if (snapshot != null) exactSnapshot(current.existing(), snapshot);
                    return current.existing();
                }
                if (!current.equals(prepared)) throw new LibraryException(409, "검색 중 대화 또는 자료 범위가 변경되었습니다. 다시 질문해 주세요.");
                if (current.missingPrior() && result.hits().isEmpty()) {
                    throw new LibraryException(409, "이전 질문의 자료가 없거나 변경되었습니다. 자료를 확인하고 주제를 명시해 주세요.");
                }
                attempt.checkpoint();
                var captured = snapshot == null ? writer.captureConversation(workspaceId, frozen.question().strip(),
                        result.hits(), frozen.attachmentIds(), current.context())
                        : writer.captureInspectedConversation(workspaceId, frozen.question().strip(), result.hits(), current.context());
                if (snapshot != null) exactSnapshot(captured, snapshot);
                attempt.checkpoint();
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void beforeCommit(boolean readOnly) {
                                attempt.checkpoint();
                                if (snapshot != null) exactSnapshot(captured, snapshot);
                            }
                        });
                return captured;
            });
        } finally {
            synchronized (inFlight) { inFlight.remove(key); }
        }
    }

    private void exactSnapshot(EvidenceBundle captured, dev.knowledgeforge.search.application.LocalSearchSnapshot snapshot) {
        if (!java.time.Instant.now().isBefore(snapshot.expiresAt()) || !captured.payload().excluded().isEmpty()
                || captured.payload().items().size() != snapshot.entries().size()) {
            throw new LibraryException(409, "검토한 검색 근거를 그대로 고정할 수 없습니다. 새 검색이 필요합니다.");
        }
        for (int index = 0; index < snapshot.entries().size(); index++) {
            var expected = snapshot.entries().get(index);
            var item = captured.payload().items().get(index);
            var hit = expected.hit();
            if (!"ORIGINAL".equals(item.kind()) || !Objects.equals(item.entryId(), hit.entryId())
                    || !Objects.equals(item.contentHash(), expected.contentHash()) || !Objects.equals(item.textHash(), hit.textHash())
                    || !Objects.equals(item.quote(), hit.snippet()) || item.startLine() != hit.startLine() || item.endLine() != hit.endLine()) {
                throw new LibraryException(409, "검색 근거가 변경되었습니다. 검토한 결과만 고정할 수 있습니다.");
            }
        }
    }

    private Prepared prepare(String workspaceId, Create request, String retrievalOverride, String generatedThreadId, boolean snapshotSearch) {
        var attachmentIds = request.attachmentIds() == null ? List.<String>of() : request.attachmentIds();
        var existing = all(workspaceId).stream().filter(bundle -> bundle.payload().chatContext() != null
                && bundle.payload().chatContext().requestId().equals(request.requestId())).findFirst();
        if (existing.isPresent()) {
            var bundle = existing.get();
            var context = bundle.payload().chatContext();
            var attached = bundle.payload().items().stream().filter(item -> item.kind().startsWith("ATTACHMENT_"))
                    .map(EvidenceBundle.Item::entryId).toList();
            if (!bundle.payload().question().equals(request.question().strip())
                    || (context.extractive() != null && !context.extractive().turnKind().equals(retrievalOverride == null ? "USER_QUESTION" : "SEARCH_CAPTURE"))
                    || !Objects.equals(context.parentBundleId(), request.parentBundleId())
                    || (retrievalOverride != null && !retrievalOverride.equals(context.retrievalQuery()))
                    || (request.threadId() != null && !context.threadId().equals(request.threadId())) || !attached.equals(attachmentIds)) {
                throw new LibraryException(409, "동일 요청 식별자의 내용이 변경되었습니다.");
            }
            return new Prepared(bundle, null, List.of(), false);
        }
        String id = request.threadId();
        String retrieval = request.question().strip();
        var history = new ArrayList<ChatTurn>();
        var questions = new ArrayList<EvidenceBundle.UserQuestion>();
        EvidenceBundle parent = null;
        if (id == null) {
            if (request.parentBundleId() != null) throw new LibraryException(400, "새 대화에는 이전 질문을 지정할 수 없습니다.");
            id = generatedThreadId == null ? UUID.randomUUID().toString() : generatedThreadId;
        } else {
            var detail = get(workspaceId, id);
            parent = detail.bundles().getLast();
            if (!parent.id().equals(request.parentBundleId())) throw new LibraryException(409, "대화가 변경되었습니다. 다시 열어 주세요.");
            if (detail.jobs().stream().anyMatch(AnswerJob::active)) throw new LibraryException(409, "현재 답변이 끝난 뒤 질문해 주세요.");
            for (var previous : detail.bundles()) {
                if (ContextPrompt.userQuestion(previous)) questions.add(ContextPrompt.question(previous));
            }
            var recent = detail.bundles().subList(Math.max(0, detail.bundles().size() - 5), detail.bundles().size());
            for (var previous : recent) {
                var answer = detail.jobs().stream().filter(job -> job.bundleId().equals(previous.id()) && job.status().equals("SUCCEEDED"))
                        .max(Comparator.comparing(AnswerJob::updatedAt));
                history.add(new ChatTurn(previous.id(), previous.digest(), previous.payload().question(),
                        answer.map(AnswerJob::answer).orElse(null)));
            }
        }
        if (retrievalOverride != null) retrieval = retrievalOverride;
        var retrievalQueries = new ArrayList<String>();
        retrievalQueries.add(retrieval);
        boolean missingPrior = false;
        if (retrievalOverride != null && !snapshotSearch && !retrieval.equals(request.question().strip())) {
            retrievalQueries.add(request.question().strip());
        } else if (retrievalOverride == null && FollowupQuestion.referential(retrieval)) {
            for (int position = questions.size() - 1; position >= Math.max(0, questions.size() - 32); position--) {
                var previousQuestion = questions.get(position);
                if (FollowupQuestion.referential(previousQuestion.question())) continue;
                if (validator.check(workspaceId, previousQuestion.bundleId()).eligible()) {
                    retrievalQueries.add(previousQuestion.question());
                } else if (!"NO_EVIDENCE".equals(bundles.get(workspaceId, previousQuestion.bundleId()).payload().status())) {
                    missingPrior = true;
                }
                break;
            }
        }
        var selection = ContextPrompt.select(questions, history, retrievalQueries, retrievalOverride != null);
        var context = new ChatContext(id, request.parentBundleId(), request.requestId(), retrieval, selection.history(), selection.extractive());
        ContextPrompt.validate(workspaceId, request.question().strip(), context, previousId -> bundles.get(workspaceId, previousId));
        return new Prepared(null, context, List.copyOf(retrievalQueries), missingPrior);
    }

    public void delete(String workspaceId, String id) {
        var detail = get(workspaceId, id);
        if (detail.jobs().stream().anyMatch(AnswerJob::active)) throw new LibraryException(409, "실행 중인 답변을 중단한 뒤 삭제하세요.");
        for (var bundle : detail.bundles()) bundles.delete(workspaceId, bundle.id());
    }
}
