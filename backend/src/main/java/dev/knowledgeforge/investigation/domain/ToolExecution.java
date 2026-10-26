package dev.knowledgeforge.investigation.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ToolExecution {
    private ToolExecution() {}

    public enum Status { OK, EMPTY, DENIED, TIMEOUT, FAILED, CANCELLED }

    public record Binding(String workspace, String environment, String scope) {
        public Binding {
            requireText(workspace);
            requireText(environment);
            requireText(scope);
        }
    }

    public record Invocation<I>(String toolId, Binding binding, I input) {
        public Invocation {
            requireText(toolId);
            Objects.requireNonNull(binding);
            Objects.requireNonNull(input);
        }
    }

    public record Budget(int maxCalls, Duration totalTime, Duration perCallTime,
                         int maxOutputCharacters, int maxCharactersPerCall) {
        public Budget {
            if (maxCalls < 1 || maxOutputCharacters < 1 || maxCharactersPerCall < 1) {
                throw new IllegalArgumentException("Budgets must be positive");
            }
            requireDuration(totalTime);
            requireDuration(perCallTime);
        }
    }

    public record Result(Status status, String output, boolean truncated, String reason) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(output);
            Objects.requireNonNull(reason);
        }
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void cancel() { cancelled.set(true); }

        public boolean isCancelled() { return cancelled.get(); }
    }

    public record Context(Binding binding, BooleanSupplier cancelled) {
        public Context {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(cancelled);
        }

        public void checkpoint() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }
    }

    @FunctionalInterface
    public interface Output {
        void append(String text);
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Blank identifier");
    }

    private static void requireDuration(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid duration");
        duration.toNanos();
    }
}
