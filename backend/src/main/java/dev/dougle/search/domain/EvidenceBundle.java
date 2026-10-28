package dev.dougle.search.domain;

import java.util.List;

public record EvidenceBundle(String id, String workspaceId, String createdAt, String digest, Payload payload) {
    public record Payload(String question, List<String> domains, boolean includeKnowledge, String status,
                          int usedCharacters, int maxCharacters, List<Item> items, List<Excluded> excluded, ChatContext chatContext) {
        public Payload(String question, List<String> domains, boolean includeKnowledge, String status,
                       int usedCharacters, int maxCharacters, List<Item> items, List<Excluded> excluded) {
            this(question, domains, includeKnowledge, status, usedCharacters, maxCharacters, items, excluded, null);
        }
    }
    public record ChatContext(String threadId, String parentBundleId, String requestId, String retrievalQuery,
                              List<ChatTurn> history, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ExtractiveContext extractive) {
        public ChatContext { history = List.copyOf(history); }
        public ChatContext(String threadId, String parentBundleId, String requestId, String retrievalQuery, List<ChatTurn> history) {
            this(threadId, parentBundleId, requestId, retrievalQuery, history, null);
        }
    }
    public record ChatTurn(String bundleId, String bundleDigest, String question, String answer) {}
    public record UserQuestion(String bundleId, String bundleDigest, String question, boolean legacyUnclassified) {
        public UserQuestion(String bundleId, String bundleDigest, String question) { this(bundleId, bundleDigest, question, false); }
    }
    public record ExtractiveContext(int version, String turnKind, int totalQuestions, int omittedQuestions, int maxSerializedCharacters,
                                    int answerCharacterLimit, int omittedAnswers, List<String> truncatedAnswerBundleIds,
                                    List<UserQuestion> questions, List<String> retrievalQueries) {
        public ExtractiveContext {
            truncatedAnswerBundleIds = List.copyOf(truncatedAnswerBundleIds);
            questions = List.copyOf(questions);
            retrievalQueries = List.copyOf(retrievalQueries);
        }
    }
    public record Item(String label, String kind, String entryId, String cardId, Integer version, String title,
                       String sourceId, String path, String sourceRevision, String contentHash, String textHash,
                       int startLine, int endLine, String quote, List<Support> supports) {}
    public record Support(String label, String snapshotId, String entryId, String sourceId, String path,
                          String name, String sourceRevision, String contentHash, int startLine, int endLine, String quote) {}
    public record Excluded(String kind, String entryId, String cardId, String reason) {}
    public record Summary(String id, String question, String status, int usedCharacters, String createdAt) {}
}
