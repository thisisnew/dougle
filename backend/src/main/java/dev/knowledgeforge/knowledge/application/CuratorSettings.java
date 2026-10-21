package dev.knowledgeforge.knowledge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CuratorSettings {
    private final String codexModel;
    private final String claudeModel;

    public CuratorSettings(@Value("${forge.ai.codex-model:}") String codexModel,
                           @Value("${forge.ai.claude-model:}") String claudeModel) {
        this.codexModel = validate(codexModel);
        this.claudeModel = validate(claudeModel);
    }

    public String requestedModel(CuratorModel.Provider provider) {
        return provider == CuratorModel.Provider.CODEX ? codexModel : claudeModel;
    }

    private String validate(String value) {
        String model = value == null ? "" : value.strip();
        if (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("AI model must be an identifier of at most 128 characters, not CLI options.");
        }
        return model;
    }
}
