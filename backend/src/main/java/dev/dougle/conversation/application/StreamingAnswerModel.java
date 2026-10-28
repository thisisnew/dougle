package dev.dougle.conversation.application;

import java.util.function.Consumer;

public interface StreamingAnswerModel extends AnswerModel {
    String generateWithText(String provider, String prompt, String requestedModel, Consumer<String> text);
}
