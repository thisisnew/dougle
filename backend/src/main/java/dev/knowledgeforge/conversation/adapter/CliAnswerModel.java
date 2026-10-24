package dev.knowledgeforge.conversation.adapter;

import dev.knowledgeforge.conversation.application.StreamingAnswerModel;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import org.springframework.stereotype.Component;

@Component
public class CliAnswerModel implements StreamingAnswerModel {
    private final CuratorModel transport;
    public CliAnswerModel(CuratorModel transport) { this.transport = transport; }
    public String generate(String provider, String prompt, String requestedModel) {
        return transport.generate(CuratorModel.Provider.valueOf(provider), prompt, requestedModel);
    }
    public String generateWithText(String provider, String prompt, String requestedModel, java.util.function.Consumer<String> text) {
        return transport.generateWithText(CuratorModel.Provider.valueOf(provider), prompt, requestedModel, text);
    }
    public String generateWithImages(String provider, String prompt, String requestedModel, java.util.List<dev.knowledgeforge.knowledge.application.ModelImage> images, java.util.function.Consumer<String> text) {
        return transport.generateWithImages(CuratorModel.Provider.valueOf(provider), prompt, requestedModel, images, text);
    }
}
