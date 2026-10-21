package dev.knowledgeforge.knowledge.application;

public interface CuratorModel {
    enum Provider { CODEX, CLAUDE }
    String generate(Provider provider, String prompt, String requestedModel);
    default String generateWithText(Provider provider, String prompt, String requestedModel, java.util.function.Consumer<String> text) {
        return generate(provider, prompt, requestedModel);
    }
}
