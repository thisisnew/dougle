package dev.knowledgeforge.conversation.application;

public interface AnswerModel {
    String generate(String provider, String prompt, String requestedModel);
    default String generateWithImages(String provider, String prompt, String requestedModel, java.util.List<dev.knowledgeforge.knowledge.application.ModelImage> images,
                                     java.util.function.Consumer<String> text) {
        if (!images.isEmpty()) throw new dev.knowledgeforge.library.application.LibraryException(400, "이 AI 연결은 이미지 입력을 지원하지 않습니다.");
        return this instanceof StreamingAnswerModel streaming ? streaming.generateWithText(provider, prompt, requestedModel, text) : generate(provider, prompt, requestedModel);
    }
}
