package dev.dougle;

import dev.dougle.sources.application.SnapshotPlanner;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SnapshotPlannerTest {
    private CollectedSnapshot.File file(String path, String content) {
        return new CollectedSnapshot.File(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void comparesPathsAndBytesIndependentlyOfRevisionAndInputOrder() {
        var stable = file("nested/stable.txt", "stable");
        var old = new CollectedSnapshot("old", List.of(stable, file("edit.txt", "before"), file("delete.txt", "gone")));
        var baseline = SnapshotPlanner.plan(old, Map.of()).hashes();
        var next = new CollectedSnapshot("new", List.of(file("added.txt", "new"), file("edit.txt", "after"), stable));
        var plan = SnapshotPlanner.plan(next, baseline);
        assertThat(plan.changed()).extracting(CollectedSnapshot.File::path).containsExactly("added.txt", "edit.txt");
        assertThat(plan.hashes()).doesNotContainKey("delete.txt");
        assertThat(plan.folders()).containsExactly("nested");
        assertThat(SnapshotPlanner.plan(new CollectedSnapshot("another revision", next.files()), plan.hashes()).changed()).isEmpty();
    }

    @Test
    void renameIsAdditionAndDeletionAndEmptySnapshotHasNoChangesToAnalyze() {
        var baseline = SnapshotPlanner.plan(new CollectedSnapshot("old", List.of(file("old.txt", "same"))), Map.of()).hashes();
        var plan = SnapshotPlanner.plan(new CollectedSnapshot("new", List.of(file("new.txt", "same"))), baseline);
        assertThat(plan.changed()).extracting(CollectedSnapshot.File::path).containsExactly("new.txt");
        assertThat(plan.hashes()).doesNotContainKey("old.txt");
        var empty = SnapshotPlanner.plan(new CollectedSnapshot("empty", List.of()), baseline);
        assertThat(empty.hashes()).isEmpty();
        assertThat(empty.folders()).isEmpty();
        assertThat(empty.changed()).isEmpty();
    }

    @Test
    void rejectsDuplicatePathsAndFileFolderCollisionsRegardlessOfOrder() {
        for (var files : List.of(List.of(file("same", "one"), file("same", "two")),
                List.of(file("tree", "one"), file("tree/child", "two")),
                List.of(file("tree/child", "two"), file("tree", "one")))) {
            assertThatThrownBy(() -> SnapshotPlanner.plan(new CollectedSnapshot("revision", files), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
