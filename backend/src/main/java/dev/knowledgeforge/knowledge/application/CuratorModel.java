package dev.knowledgeforge.knowledge.application;

public interface CuratorModel {
    enum Provider { CODEX, CLAUDE }
    String generate(Provider provider, String prompt, String requestedModel);
}
