package dev.dougle.knowledge.application;

import java.util.List;

public interface CliDiscovery {
    record Candidate(String name, String path) {}
    List<Candidate> find(CuratorModel.Provider provider);

    default String defaultCommand(CuratorModel.Provider provider, String fallback) {
        if (!fallback.equals(provider.name().toLowerCase(java.util.Locale.ROOT))) return fallback;
        var candidates = find(provider);
        if (candidates.size() == 1) return candidates.getFirst().path();
        return candidates.stream().filter(candidate -> candidate.name().equals(fallback))
                .map(Candidate::path).findFirst().orElse(fallback);
    }
}
