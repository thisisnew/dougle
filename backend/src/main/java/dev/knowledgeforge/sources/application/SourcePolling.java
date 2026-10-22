package dev.knowledgeforge.sources.application;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SourcePolling {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SourcePolling.class);
    private final SourceStore store;
    private final SourceService sources;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(2);

    public SourcePolling(SourceStore store, SourceService sources) {
        this.store = store;
        this.sources = sources;
    }

    @Scheduled(fixedDelayString = "${forge.polling.tick-ms:1000}", initialDelayString = "${forge.polling.initial-delay-ms:5000}")
    public void tick() {
        if (workers.isShutdown() || capacity.availablePermits() == 0) return;
        for (var source : store.due(2)) {
            if (!capacity.tryAcquire()) break;
            try {
                workers.execute(() -> {
                    try {
                        sources.poll(source.workspaceId(), source.id());
                    } catch (Exception exception) {
                        LOG.warn("Scheduled Source {} failed", source.id(), exception);
                    } finally {
                        capacity.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException exception) {
                capacity.release();
                return;
            }
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
