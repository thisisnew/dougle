package dev.dougle.ingestion.application;

import java.util.function.Consumer;

public final class ProcessingProgress {
    public record Step(String stage, String file, long completed, long total) {}
    private static final ThreadLocal<Consumer<Step>> LISTENER = new ThreadLocal<>();

    private ProcessingProgress() {}

    public static AutoCloseable observe(Consumer<Step> listener) {
        Consumer<Step> previous = LISTENER.get();
        LISTENER.set(listener);
        return () -> {
            if (previous == null) LISTENER.remove();
            else LISTENER.set(previous);
        };
    }

    public static void report(String stage, String file, long completed, long total) {
        Consumer<Step> listener = LISTENER.get();
        if (listener != null) listener.accept(new Step(stage, file, completed, total));
    }
}
