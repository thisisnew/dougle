package dev.dougle.search.application;

import dev.dougle.library.application.LibraryException;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.library.domain.Workspace;
import dev.dougle.knowledge.application.KnowledgeStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchReadinessTest {
    @Test void indexingReturnsPreparingPromptlyAndRecoversAfterCommit() throws Exception {
        var catalog = mock(SearchCatalog.class);
        var index = mock(SearchIndex.class);
        var library = mock(LibraryStore.class);
        when(index.questionReady()).thenReturn(true);
        when(library.workspace("workspace")).thenReturn(Optional.of(mock(Workspace.class)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(catalog.changes()).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return List.of();
        });
        var service = new SearchService(catalog, index, library, mock(KnowledgeStore.class));
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var refresh = worker.submit(service::refresh);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    assertThatThrownBy(service::requireQuestionReady).isInstanceOf(LibraryException.class);
                    assertThatThrownBy(() -> service.searchQuestion("workspace", "feature flag", List.of(), false))
                            .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(503))
                            .hasMessageContaining("검색용으로 준비");
                    assertThatThrownBy(() -> service.search("workspace", "feature flag", List.of()))
                            .isInstanceOf(LibraryException.class);
                });
                verifyNoInteractions(library);
            } finally { release.countDown(); }
            refresh.get(2, TimeUnit.SECONDS);
        }
        when(index.searchQuestion("workspace", "feature flag", List.of(), false)).thenReturn(List.of());
        assertThat(service.searchQuestion("workspace", "feature flag", List.of(), false)).isEmpty();
        verify(library).workspace("workspace");
    }
}
