package dev.knowledgeforge.plugins.slack.adapter;

import dev.knowledgeforge.plugins.slack.application.SlackReconnectScheduler;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class SlackReconnectExecutor implements SlackReconnectScheduler {
    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    @Override public Pending schedule(Runnable action, int seconds) {
        var future = executor.schedule(action, seconds, TimeUnit.SECONDS);
        return () -> future.cancel(false);
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
