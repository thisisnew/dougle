package dev.dougle.knowledge.domain;

import java.util.List;

public final class Publication {
    private Publication() {}

    public record Review(int version, String reviewer, String note, String reviewedAt) {}
    public record Release(int revision, int version, String publishedAt) {}
    public record State(int latestVersion, Review review, Release publication,
                        List<Knowledge.Evidence> publishedEvidence) {}
}
