package dev.knowledgeforge.knowledge.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CuratorSettings {
    private final String codexModel;
    private final String claudeModel;
    private final CliLaunchConfiguration launch;

    @org.springframework.beans.factory.annotation.Autowired
    public CuratorSettings(@Value("${forge.ai.codex-model:}") String codexModel,
                           @Value("${forge.ai.claude-model:}") String claudeModel, CliLaunchConfiguration launch) {
        this.codexModel = validate(codexModel);
        this.claudeModel = validate(claudeModel);
        this.launch = launch;
    }

    public CuratorSettings(String codexModel, String claudeModel) { this(codexModel, claudeModel, null); }

    public String executionBinding(CuratorModel.Provider provider) { return launch == null ? "" : launch.binding(provider); }

    public String requestedModel(CuratorModel.Provider provider) {
        return provider == CuratorModel.Provider.CODEX ? codexModel : claudeModel;
    }

    public String resolve(CuratorModel.Provider provider, String requestedModel) {
        if (requestedModel == null) return requestedModel(provider);
        return normalize(requestedModel);
    }

    public static String normalize(String requestedModel) {
        try { return validate(requestedModel); }
        catch (IllegalArgumentException exception) {
            throw new dev.knowledgeforge.library.application.LibraryException(400,
                    "모델 ID는 영문·숫자로 시작하는 128자 이하 식별자여야 합니다. CLI 옵션은 사용할 수 없습니다.");
        }
    }

    private static String validate(String value) {
        String model = value == null ? "" : value.strip();
        if (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("AI model must be an identifier of at most 128 characters, not CLI options.");
        }
        return model;
    }
}
