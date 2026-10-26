package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.knowledge.application.CuratorModel.Provider;
import dev.knowledgeforge.knowledge.application.CuratorSettings;
import dev.knowledgeforge.search.application.EvidenceAnswerPreview;
import dev.knowledgeforge.search.application.EvidenceBundleService;
import dev.knowledgeforge.search.application.ContextPrompt;
import dev.knowledgeforge.search.domain.ContentDigest;
import dev.knowledgeforge.security.application.CredentialContentGuard;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class InvestigationPlanPreview {
    public static final int MAX_CHARACTERS = 20000;
    public static final int MAX_OUTPUT_CHARACTERS = 6000;
    public static final int MAX_SECONDS = 90;
    private final EvidenceAnswerPreview answers;
    private final EvidenceBundleService bundles;
    private final CuratorSettings settings;
    private final JsonMapper json = JsonMapper.builder().build();

    public record Material(String provider, String requestedModel, String prompt, String bindingDigest) {}

    public InvestigationPlanPreview(EvidenceAnswerPreview answers, EvidenceBundleService bundles, CuratorSettings settings) {
        this.answers = answers;
        this.bundles = bundles;
        this.settings = settings;
    }

    public Material prepare(String workspaceId, String bundleId, String provider, String requestedModel) {
        var bundle = bundles.get(workspaceId, bundleId);
        boolean extractive = bundle.payload().chatContext() != null && bundle.payload().chatContext().extractive() != null;
        CredentialContentGuard.requireSafeTree(json.valueToTree(bundle.payload()));
        if (bundle.payload().items().stream().anyMatch(item -> item.kind().equals("ATTACHMENT_IMAGE"))) {
            throw new LibraryException(400, "자료 검색 계획은 텍스트 근거만 지원합니다.");
        }
        String reference;
        String referenceDigest;
        String resolvedProvider;
        String resolvedModel;
        if (bundle.payload().items().isEmpty() && "NO_EVIDENCE".equals(bundle.payload().status())) {
            Provider selected;
            try { selected = Provider.valueOf(provider); }
            catch (IllegalArgumentException | NullPointerException invalid) {
                throw new LibraryException(400, "CODEX 또는 CLAUDE 제공자를 선택하세요.");
            }
            resolvedProvider = selected.name();
            resolvedModel = settings.resolve(selected, requestedModel);
            var context = bundle.payload().chatContext();
            var data = json.createObjectNode().put("question", bundle.payload().question()).put("evidenceStatus", "NO_EVIDENCE");
            if (context != null) {
                ContextPrompt.validate(workspaceId, bundle.payload().question(), context, previousId -> bundles.get(workspaceId, previousId));
                if (!extractive) data.set("conversation", json.valueToTree(context.history()));
            }
            reference = (extractive ? ContextPrompt.prefix(context) : "") + data;
            referenceDigest = ContentDigest.of(json.createObjectNode().put("purpose", extractive ? "QUESTION_ONLY_SEARCH_V2_EXTRACTIVE" : "QUESTION_ONLY_SEARCH_V1")
                    .put("bundleDigest", bundle.digest()).put("executionBinding", settings.executionBinding(selected))
                    .put("reference", reference).toString());
        } else {
            var answer = answers.prepare(workspaceId, bundleId, provider, requestedModel);
            reference = answer.prompt();
            referenceDigest = answer.digest();
            resolvedProvider = answer.provider();
            resolvedModel = answer.requestedModel();
        }
        String prompt = ContextPrompt.planPrompt(reference);
        if (prompt.length() > MAX_CHARACTERS) throw new LibraryException(413, "검색 계획 요청이 20,000자를 초과합니다.");
        var binding = json.createObjectNode().put("purpose", extractive ? "LOCAL_INVESTIGATION_PLAN_V5_EXTRACTIVE" : "LOCAL_INVESTIGATION_PLAN_V4")
                .put("planSchema", "decision-query-nullable-operational-proposal:v3")
                .put("workspaceId", workspaceId).put("bundleId", bundleId).put("referenceDigest", referenceDigest)
                .put("provider", resolvedProvider).put("requestedModel", resolvedModel)
                .put("prompt", prompt).put("toolSchema", "local.workspace.search:LocalSearchQuery:v1")
                .put("maxCalls", 1).put("maxToolCalls", 1).put("maxSeconds", MAX_SECONDS)
                .put("toolSeconds", 10).put("maxOutputCharacters", MAX_OUTPUT_CHARACTERS);
        return new Material(resolvedProvider, resolvedModel, prompt, ContentDigest.of(binding.toString()));
    }
}
