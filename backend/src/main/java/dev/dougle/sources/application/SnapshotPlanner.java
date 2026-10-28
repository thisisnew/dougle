package dev.dougle.sources.application;

import dev.dougle.sources.domain.CollectedSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SnapshotPlanner {
    private SnapshotPlanner() {}

    public record Plan(Map<String, String> hashes, Set<String> folders,
                       List<CollectedSnapshot.File> changed) {}

    public static Plan plan(CollectedSnapshot snapshot, Map<String, String> baseline) {
        Map<String, String> hashes = new LinkedHashMap<>();
        Set<String> folders = new HashSet<>();
        List<CollectedSnapshot.File> changed = new ArrayList<>();
        for (var file : snapshot.files()) {
            String hash = file.hash();
            if (hashes.putIfAbsent(file.path(), hash) != null) {
                throw new IllegalArgumentException("Duplicate snapshot path");
            }
            for (int slash = file.path().indexOf('/'); slash >= 0; slash = file.path().indexOf('/', slash + 1)) {
                folders.add(file.path().substring(0, slash));
            }
            if (!hash.equals(baseline.get(file.path()))) changed.add(file);
        }
        if (folders.stream().anyMatch(hashes::containsKey)) {
            throw new IllegalArgumentException("Snapshot path is both file and folder");
        }
        return new Plan(Map.copyOf(hashes), Set.copyOf(folders), List.copyOf(changed));
    }

}
