package dev.dougle.sources.application;

import dev.dougle.sources.domain.Source;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourcePollingTest {
    @Test
    void limitsWorkersWithoutQueuingAndInterruptsOnShutdown() throws Exception {
        SourceStore store = mock(SourceStore.class);
        SourceService service = mock(SourceService.class);
        Source first = new Source("one", "workspace", "one", "URL", "https://example.com", "", "NEW", null, null, null, null, true, 60, 1L, 0, 1, null);
        Source second = new Source("two", "workspace", "two", "URL", "https://example.com", "", "NEW", null, null, null, null, true, 60, 1L, 0, 1, null);
        when(store.due(2)).thenReturn(List.of(first, second));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        doAnswer(invocation -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return null;
        }).when(service).poll(anyString(), anyString());
        SourcePolling polling = new SourcePolling(store, service);
        try {
            polling.tick();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int tick = 0; tick < 20; tick++) polling.tick();
            verify(store, times(1)).due(2);
            verify(service, times(2)).poll(anyString(), anyString());
        } finally { polling.close(); }
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        polling.tick();
        verify(store, times(1)).due(2);
    }
}
