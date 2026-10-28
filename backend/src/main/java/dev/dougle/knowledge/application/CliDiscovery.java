package dev.dougle.knowledge.application;

import java.util.List;

public interface CliDiscovery {
    record Candidate(String name, String path) {}
    List<Candidate> find(CuratorModel.Provider provider);

    default String defaultCommand(CuratorModel.Provider provider, String fallback) {
        var candidates = find(provider);
        if (!fallback.equals(provider.name().toLowerCase(java.util.Locale.ROOT))) return fallback;
        var aliases = candidates.stream().filter(candidate -> candidate.path().startsWith("dougle-alias-")).toList();
        if (aliases.size() == 1) return aliases.getFirst().path();
        if (candidates.size() == 1) return candidates.getFirst().path();
        return candidates.stream().filter(candidate -> candidate.name().equals(fallback))
                .map(Candidate::path).findFirst().orElse(fallback);
    }
}
