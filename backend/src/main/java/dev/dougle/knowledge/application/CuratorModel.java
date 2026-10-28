package dev.dougle.knowledge.application;

public interface CuratorModel {
    enum Provider { CODEX, CLAUDE }
    String generate(Provider provider, String prompt, String requestedModel);
    default String generatePlan(Provider provider, String prompt, String requestedModel) {
        throw new dev.dougle.library.application.LibraryException(400, "이 AI 연결은 검색 계획을 지원하지 않습니다.");
    }
    default String generateWithText(Provider provider, String prompt, String requestedModel, java.util.function.Consumer<String> text) {
        return generate(provider, prompt, requestedModel);
    }
    default String generateWithImages(Provider provider, String prompt, String requestedModel, java.util.List<ModelImage> images, java.util.function.Consumer<String> text) {
        if (!images.isEmpty()) throw new dev.dougle.library.application.LibraryException(400, "이 AI 연결은 이미지 입력을 지원하지 않습니다.");
        return generateWithText(provider, prompt, requestedModel, text);
    }
}
