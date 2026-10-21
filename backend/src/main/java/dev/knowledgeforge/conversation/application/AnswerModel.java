package dev.knowledgeforge.conversation.application;

public interface AnswerModel {
    String generate(String provider, String prompt, String requestedModel);
}
