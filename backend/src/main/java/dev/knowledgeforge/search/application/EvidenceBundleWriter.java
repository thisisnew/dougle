package dev.knowledgeforge.search.application;

import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.knowledge.application.PublicationStore;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import dev.knowledgeforge.search.domain.EvidenceBundle.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceBundleWriter {
    private final KnowledgeStore knowledge;
    private final PublicationStore publications;
    private final LibraryStore library;
    private final EvidenceBundleStore bundles;
    private final SearchCatalog catalog;
    private final dev.knowledgeforge.attachment.application.AttachmentService attachments;

    public EvidenceBundleWriter(KnowledgeStore knowledge, PublicationStore publications, LibraryStore library, EvidenceBundleStore bundles, SearchCatalog catalog,
                                dev.knowledgeforge.attachment.application.AttachmentService attachments) {
        this.knowledge = knowledge;
        this.publications = publications;
        this.library = library;
        this.bundles = bundles;
        this.catalog = catalog;
        this.attachments = attachments;
    }

    @Transactional
    public EvidenceBundle capture(String workspaceId, String question, List<String> domains, boolean includeKnowledge, List<SearchIndex.Hit> hits) {
        return capture(workspaceId, question, domains, includeKnowledge, hits, List.of());
    }

    @Transactional
    public EvidenceBundle capture(String workspaceId, String question, List<String> domains, boolean includeKnowledge, List<SearchIndex.Hit> hits, List<String> attachmentIds) {
        return capture(workspaceId, question, domains, includeKnowledge, hits, attachmentIds, null);
    }

    @Transactional
    public EvidenceBundle capture(String workspaceId, String question, List<String> domains, boolean includeKnowledge, List<SearchIndex.Hit> hits, List<String> attachmentIds, ChatContext chatContext) {
        if (library.workspace(workspaceId).isEmpty()) throw new LibraryException(404, "Workspace를 찾을 수 없습니다.");
        if (bundles.list(workspaceId).size() >= 100) throw new LibraryException(409, "근거 묶음은 Workspace당 최대 100개입니다. 불필요한 묶음을 삭제하세요.");
        List<Item> items = new ArrayList<>();
        List<Excluded> excluded = new ArrayList<>();
        boolean extractive = chatContext != null && chatContext.extractive() != null;
        int maxCharacters = ContextPrompt.evidenceCharacters(chatContext);
        int used = 0;
        for (var attached : attachments.pending(workspaceId, attachmentIds)) {
            items.add(new Item("R" + (items.size() + 1), attached.image() ? "ATTACHMENT_IMAGE" : "ATTACHMENT_TEXT",
                    attached.id(), null, null, attached.name(), null, null, attached.truncated() ? "TRUNCATED" : null,
                    attached.contentHash(), ContentDigest.of(attached.text()), attached.image() ? 0 : 1,
                    attached.image() ? 0 : attached.text().split("\n", -1).length, attached.text(), List.of()));
            used += attached.text().length();
            if (extractive && (used > maxCharacters || !ContextPrompt.withinRequestBudgets(EvidenceAnswerPreview.renderPrompt(question, items, chatContext)))) {
                throw new LibraryException(413, "선택한 첨부와 대화 문맥이 요청 예산을 넘습니다. 첨부를 줄이거나 새 대화를 시작해 주세요.");
            }
        }
        for (var hit : hits) {
            String reason = null;
            Item item = null;
            String label = "R" + (items.size() + 1);
            var current = catalog.document("KNOWLEDGE".equals(hit.kind()) ? "knowledge:" + hit.cardId() : hit.entryId()).orElse(null);
            if (current == null || !current.workspaceId().equals(workspaceId)) reason = "REMOVED";
            else if (!domains.isEmpty() && current.domains().stream().noneMatch(domains::contains)) reason = "SCOPE_CHANGED";
            else if (!hit.bodyMatch()) reason = "NO_BODY_MATCH";
            else if (items.size() >= 5) reason = "ITEM_LIMIT";
            else if (used >= maxCharacters) reason = extractive ? "CONTEXT_PROMPT_LIMIT" : "CHARACTER_LIMIT";
            else if ("ORIGINAL".equals(hit.kind())) {
                var material = knowledge.material(workspaceId, hit.entryId()).orElse(null);
                if (material == null) reason = "REMOVED";
                else if (!List.of("READY", "PARTIAL").contains(material.analysisStatus())
                        || !ContentDigest.of(material.text()).equals(hit.textHash())) reason = "INDEX_CHANGED";
                else {
                    var window = dev.knowledgeforge.search.domain.EvidenceWindow.around(material.text(), hit.snippet(), hit.startLine(), hit.endLine(), maxCharacters - used);
                    item = new Item(label, hit.kind(), hit.entryId(), null, null, material.name(), material.sourceId(),
                        material.path() == null ? material.name() : material.path(), material.revision(), ContentDigest.of(material.content()),
                        hit.textHash(), window.startLine(), window.endLine(), window.text(), List.of());
                }
            } else if ("KNOWLEDGE".equals(hit.kind())) {
                var version = knowledge.version(workspaceId, hit.cardId(), hit.version()).orElse(null);
                var release = publications.publication(hit.cardId()).orElse(null);
                if (version == null) reason = "REMOVED";
                else if (release == null || release.version() != hit.version()) reason = "NO_LONGER_PUBLISHED";
                else if (!ContentDigest.of(version.markdown()).equals(hit.textHash())) reason = "INDEX_CHANGED";
                else if (version.evidence().isEmpty() || version.evidence().stream().anyMatch(value -> !"CURRENT".equals(value.freshness()))) reason = "NOT_CURRENT";
                else {
                    List<Support> supports = new ArrayList<>();
                    for (var evidence : version.evidence()) {
                        supports.add(new Support("E" + (supports.size() + 1), evidence.snapshotId(), evidence.entryId(), evidence.sourceId(),
                                evidence.path(), evidence.name(), evidence.sourceRevision(), evidence.contentHash(),
                                evidence.startLine(), evidence.endLine(), evidence.quote()));
                    }
                    item = new Item(label, hit.kind(), null, hit.cardId(), hit.version(), version.title(), null, null, null,
                            null, hit.textHash(), hit.startLine(), hit.endLine(), hit.snippet(), List.copyOf(supports));
                }
            } else reason = "UNSUPPORTED";
            if (item != null) {
                int characters = item.quote().length() + item.supports().stream().mapToInt(value -> value.quote().length()).sum();
                var candidate = new ArrayList<>(items);
                candidate.add(item);
                if (used + characters > maxCharacters) reason = extractive ? "CONTEXT_PROMPT_LIMIT" : "CHARACTER_LIMIT";
                else if (extractive && !ContextPrompt.withinRequestBudgets(EvidenceAnswerPreview.renderPrompt(question, candidate, chatContext))) reason = "CONTEXT_PROMPT_LIMIT";
                else { items.add(item); used += characters; }
            }
            if (reason != null) excluded.add(new Excluded(hit.kind(), hit.entryId(), hit.cardId(), reason));
        }
        var bundle = bundles.save(workspaceId, new Payload(question, List.copyOf(domains), includeKnowledge,
                items.isEmpty() ? "NO_EVIDENCE" : "READY", used, maxCharacters, List.copyOf(items), List.copyOf(excluded), chatContext));
        attachments.bind(workspaceId, attachmentIds, bundle.id());
        return bundle;
    }
}
