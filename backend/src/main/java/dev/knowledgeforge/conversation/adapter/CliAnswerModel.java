package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.AnswerModel;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import org.springframework.stereotype.Component;

@Component
public class CliAnswerModel implements AnswerModel {
    private final CuratorModel transport;
    public CliAnswerModel(CuratorModel transport) { this.transport = transport; }
    public String generate(String provider, String prompt, String requestedModel) {
        return transport.generate(CuratorModel.Provider.valueOf(provider), prompt, requestedModel);
    }
}
