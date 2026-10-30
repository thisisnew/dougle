package dev.dougle.sources.application;

import dev.dougle.search.application.SearchService;
import dev.dougle.sources.domain.Source;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceSyncJobsTest {
    @Test void longCollectionReturnsImmediatelyReattachesAndIsWorkspaceBound() throws Exception {
        var sources = mock(SourceService.class);
        var search = mock(SearchService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Source source = mock(Source.class);
        when(sources.sync(eq("one"), eq("source"), any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return source;
        });
        var jobs = new SourceSyncJobs(sources, search);
        try {
            var first = jobs.start("one", "source");
            assertThat(first.state()).isEqualTo("RUNNING");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.start("one", "source").id()).isEqualTo(first.id());
            assertThatThrownBy(() -> jobs.get("other", "source", first.id())).hasMessageContaining("찾을 수 없습니다");
            assertThatThrownBy(() -> jobs.get("one", "other", first.id())).hasMessageContaining("찾을 수 없습니다");
            release.countDown();
            for (int attempt = 0; attempt < 100 && jobs.get("one", "source", first.id()).state().equals("RUNNING"); attempt++) Thread.sleep(10);
            assertThat(jobs.get("one", "source", first.id()).source()).isSameAs(source);
            assertThat(jobs.get("one", "source", first.id()).state()).isEqualTo("COMPLETED");
            verify(sources, times(1)).sync(eq("one"), eq("source"), any());
        } finally { release.countDown(); jobs.close(); }
    }

    @Test void unexpectedErrorsDoNotExposeInternalDetails() throws Exception {
        var sources = mock(SourceService.class);
        when(sources.sync(any(), any(), any())).thenThrow(new IllegalStateException("secret-path"));
        var jobs = new SourceSyncJobs(sources, mock(SearchService.class));
        try {
            var first = jobs.start("one", "source");
            for (int attempt = 0; attempt < 100 && jobs.get("one", "source", first.id()).state().equals("RUNNING"); attempt++) Thread.sleep(10);
            var failed = jobs.get("one", "source", first.id());
            assertThat(failed.state()).isEqualTo("FAILED");
            assertThat(failed.error()).doesNotContain("secret-path").contains("다시 시도");
        } finally { jobs.close(); }
    }
}
