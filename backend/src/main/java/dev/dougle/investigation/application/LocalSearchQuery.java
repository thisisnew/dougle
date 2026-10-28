package dev.dougle.investigation.application;

public record LocalSearchQuery(String question) {
    public LocalSearchQuery {
        if (question == null || question.isBlank() || question.length() > 256) {
            throw new IllegalArgumentException("Question must contain 1 to 256 characters");
        }
        question = question.strip();
    }
}
