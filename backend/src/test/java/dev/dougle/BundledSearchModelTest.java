package dev.dougle;

import dev.dougle.search.adapter.LocalSearchModel;
import dev.dougle.search.application.SearchIndex;
import dev.dougle.search.application.SearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BundledSearchModelTest {
    @TempDir Path directory;

    @Test void missingBundledFilesNeverDownloadOrSilentlyEnableKeywordQuestions() throws Exception {
        Path data = directory.resolve("data");
        try (var model = new LocalSearchModel(data.toString(), directory.resolve("bundle"), true)) {
            assertThat(model.status().state()).isEqualTo("STARTING");
            assertThat(model.preparing()).isTrue();
            Runnable rebuild = mock(Runnable.class);
            model.prepare(rebuild);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!"ERROR".equals(model.status().state()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(model.status().state()).isEqualTo("ERROR");
            assertThat(model.ready()).isFalse();
            assertThat(model.preparing()).isTrue();
            assertThat(Files.exists(data)).isFalse();
            verifyNoInteractions(rebuild);
        }
    }

    @Test void pendingIndexRejectsQuestionsBeforeTakingTheRebuildLock() throws Exception {
        var index = mock(SearchIndex.class);
        when(index.questionReady()).thenReturn(false);
        var service = new SearchService(null, index, null, null);
        service.initialize();
        verify(index, never()).reset();
        assertThatThrownBy(() -> service.searchQuestion("one", "가상 질문", java.util.List.of(), false))
                .hasMessageContaining("검색을 준비");
        verify(index, never()).searchQuestion(any(), any(), any(), anyBoolean());
    }
}
