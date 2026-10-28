package dev.dougle.transfer.application;

import java.util.List;
import java.util.UUID;

public record WorkspaceArchiveData(int version, String workspaceName, List<Material> materials, List<ThreadData> threads) {
    public record Material(UUID key, UUID parentKey, String name, String kind, String createdAt) {}
    public record ThreadData(List<Turn> turns) {}
    public record Turn(UUID key, String createdAt, String question, String retrievalQuery, List<Evidence> evidence,
                       List<History> history, List<Answer> answers, dev.dougle.search.domain.EvidenceBundle.ExtractiveContext extractive) {
        public Turn(UUID key, String createdAt, String question, String retrievalQuery, List<Evidence> evidence,
                    List<History> history, List<Answer> answers) {
            this(key, createdAt, question, retrievalQuery, evidence, history, answers, null);
        }
    }
    public record Evidence(UUID materialKey, String contentHash, String textHash, int startLine, int endLine, String quote) {}
    public record History(UUID turnKey, String question, String answer) {}
    public record Answer(String provider, String model, String text, String createdAt, String updatedAt) {}
}
