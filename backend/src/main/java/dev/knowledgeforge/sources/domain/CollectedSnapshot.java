package dev.knowledgeforge.sources.domain;

import java.util.List;

public record CollectedSnapshot(String revision, List<File> files) {
    public record File(String path, byte[] content) {}
}
