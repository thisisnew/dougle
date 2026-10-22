package dev.knowledgeforge.sources.domain;

import java.util.List;

public record CollectedSnapshot(String revision, List<File> files, HttpValidators validators) {
    public CollectedSnapshot(String revision, List<File> files) { this(revision, files, null); }
    public record File(String path, byte[] content) {}
}
