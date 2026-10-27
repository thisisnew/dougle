package dev.knowledgeforge.search.application;

import dev.knowledgeforge.knowledge.application.KnowledgeStore;
import dev.knowledgeforge.knowledge.application.PublicationStore;
import dev.knowledgeforge.search.domain.BundleValidation;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceBundleValidator {
    private final EvidenceBundleService bundles;
    private final KnowledgeStore knowledge;
    private final EvidenceSourceStore sources;
    private final PublicationStore publications;
    private final SearchCatalog catalog;
    private final dev.knowledgeforge.attachment.application.AttachmentService attachments;

    public EvidenceBundleValidator(EvidenceBundleService bundles, KnowledgeStore knowledge,
                                   PublicationStore publications, SearchCatalog catalog, dev.knowledgeforge.attachment.application.AttachmentService attachments) {
        this(bundles, knowledge, publications, catalog, attachments, EvidenceSourceStore.legacy(knowledge));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EvidenceBundleValidator(EvidenceBundleService bundles, KnowledgeStore knowledge,
                                   PublicationStore publications, SearchCatalog catalog, dev.knowledgeforge.attachment.application.AttachmentService attachments,
                                   EvidenceSourceStore sources) {
        this.bundles = bundles;
        this.knowledge = knowledge;
        this.sources = sources;
        this.publications = publications;
        this.catalog = catalog;
        this.attachments = attachments;
    }

    @Transactional(readOnly = true)
    public BundleValidation check(String workspaceId, String id) {
        var bundle = bundles.get(workspaceId, id);
        List<BundleValidation.Item> results = new ArrayList<>();
        for (var item : bundle.payload().items()) {
            results.add(switch (item.kind()) {
                case "ORIGINAL" -> original(workspaceId, bundle, item);
                case "KNOWLEDGE" -> published(workspaceId, bundle, item);
                case "ATTACHMENT_IMAGE", "ATTACHMENT_TEXT" -> attached(workspaceId, bundle, item);
                default -> result(item, "INVALID_BUNDLE");
            });
        }
        boolean empty = results.isEmpty();
        boolean eligible = !empty && "READY".equals(bundle.payload().status())
                && results.stream().allMatch(value -> "CURRENT".equals(value.status()));
        return new BundleValidation(id, bundle.digest(), Instant.now().toString(),
                empty ? "NO_EVIDENCE" : eligible ? "CURRENT" : "BLOCKED", eligible, List.copyOf(results));
    }

    private BundleValidation.Item original(String workspaceId, EvidenceBundle bundle, EvidenceBundle.Item item) {
        var material = sources.currentMaterial(workspaceId, item.entryId(), item.sourceId(), item.path()).orElse(null);
        if (material == null) return result(item, "MISSING");
        String status;
        if (!Objects.equals(material.sourceId(), item.sourceId()) || !material.name().equals(item.title())) status = "ORIGIN_CHANGED";
        else if (!List.of("READY", "PARTIAL").contains(material.analysisStatus())) status = "ANALYSIS_UNAVAILABLE";
        else if (!material.contentHash().equals(item.contentHash())) status = "CONTENT_CHANGED";
        else if (!material.textHash().equals(item.textHash())) status = "TEXT_CHANGED";
        else if (!inScope(workspaceId, material.entryId(), bundle.payload().domains())) status = "SCOPE_CHANGED";
        else status = "CURRENT";
        return new BundleValidation.Item(item.label(), status, material.entryId(), material.revision(), List.of());
    }

    private BundleValidation.Item attached(String workspaceId, EvidenceBundle bundle, EvidenceBundle.Item item) {
        try {
            var current = attachments.get(workspaceId, item.entryId());
            boolean same = bundle.id().equals(current.bundleId()) && current.name().equals(item.title())
                    && current.contentHash().equals(item.contentHash()) && ContentDigest.of(current.text()).equals(item.textHash())
                    && current.text().equals(item.quote()) && current.image() == item.kind().equals("ATTACHMENT_IMAGE");
            return result(item, same ? "CURRENT" : "CONTENT_CHANGED");
        } catch (dev.knowledgeforge.library.application.LibraryException exception) {
            return result(item, exception.status() == 404 ? "MISSING" : "CONTENT_CHANGED");
        }
    }

    private BundleValidation.Item published(String workspaceId, EvidenceBundle bundle, EvidenceBundle.Item item) {
        var version = knowledge.version(workspaceId, item.cardId(), item.version()).orElse(null);
        if (version == null) return result(item, "MISSING");
        var publication = publications.publication(item.cardId()).orElse(null);
        if (publication == null || publication.version() != item.version()) return result(item, "PUBLICATION_CHANGED");
        if (!bundle.payload().includeKnowledge()) return result(item, "INVALID_BUNDLE");
        if (!ContentDigest.of(version.markdown()).equals(item.textHash()) || !version.title().equals(item.title())) return result(item, "TEXT_CHANGED");
        if (version.evidence().isEmpty() || version.evidence().size() != item.supports().size()) return result(item, "SUPPORT_CHANGED");
        List<BundleValidation.Support> supports = new ArrayList<>();
        for (int index = 0; index < item.supports().size(); index++) {
            var frozen = item.supports().get(index);
            var current = version.evidence().get(index);
            boolean same = frozen.label().equals("E" + (index + 1)) && frozen.snapshotId().equals(current.snapshotId())
                    && frozen.startLine() == current.startLine() && frozen.endLine() == current.endLine()
                    && frozen.quote().equals(current.quote()) && frozen.contentHash().equals(current.contentHash());
            supports.add(new BundleValidation.Support(frozen.label(), same ? current.freshness() : "SUPPORT_CHANGED"));
        }
        String status = supports.stream().anyMatch(value -> !"CURRENT".equals(value.status())) ? "SUPPORT_CHANGED"
                : inScope(workspaceId, "knowledge:" + item.cardId(), bundle.payload().domains()) ? "CURRENT" : "SCOPE_CHANGED";
        return new BundleValidation.Item(item.label(), status, null, null, List.copyOf(supports));
    }

    private boolean inScope(String workspaceId, String documentId, List<String> domains) {
        return catalog.document(documentId).filter(document -> document.workspaceId().equals(workspaceId)
                && (domains.isEmpty() || document.domains().stream().anyMatch(domains::contains))).isPresent();
    }

    private BundleValidation.Item result(EvidenceBundle.Item item, String status) {
        return new BundleValidation.Item(item.label(), status, null, null, List.of());
    }
}
