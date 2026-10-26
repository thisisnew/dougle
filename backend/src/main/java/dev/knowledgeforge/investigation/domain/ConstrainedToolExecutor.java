package dev.knowledgeforge.investigation.domain;

import static dev.knowledgeforge.investigation.domain.ToolExecution.Status.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConstrainedToolExecutor implements AutoCloseable {
    private final Map<String, Registered<?>> registry;
    private final ToolExecution.Binding binding;
    private final ToolExecution.Budget budget;
    private final ToolExecution.Cancellation cancellation;
    private final long started = System.nanoTime();
    private final Set<Key> seen = new HashSet<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 100, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "investigation-tool");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean closed;
    private boolean busy;
    private boolean toolRunning;
    private int calls;
    private int outputCharacters;
    private Future<ToolExecution.Result> active;

    public ConstrainedToolExecutor(Collection<? extends ReadOnlyTool<?>> tools,
                                   ToolExecution.Binding binding, ToolExecution.Budget budget,
                                   ToolExecution.Cancellation cancellation) {
        this.binding = Objects.requireNonNull(binding);
        this.budget = Objects.requireNonNull(budget);
        this.cancellation = Objects.requireNonNull(cancellation);
        Map<String, Registered<?>> registered = new HashMap<>();
        for (ReadOnlyTool<?> tool : tools) {
            String id = Objects.requireNonNull(tool.id());
            if (!id.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid tool ID");
            if (registered.putIfAbsent(id, register(tool)) != null) {
                throw new IllegalArgumentException("Duplicate tool ID");
            }
        }
        registry = Map.copyOf(registered);
    }

    public ToolExecution.Result execute(ToolExecution.Invocation<?> invocation) {
        Objects.requireNonNull(invocation);
        long callStarted = System.nanoTime();
        Registered<?> tool;
        BoundedOutput output;
        Future<ToolExecution.Result> future;
        synchronized (this) {
            ToolExecution.Result stopped = stopped(callStarted);
            if (stopped != null) return stopped;
            tool = registry.get(invocation.toolId());
            if (!binding.equals(invocation.binding())) return result(DENIED, "binding_mismatch");
            if (tool == null) return result(DENIED, "unknown_tool");
            if (!tool.bindings().contains(binding)) return result(DENIED, "scope_denied");
            if (!tool.inputType().isInstance(invocation.input())) return result(DENIED, "input_type");
            if (busy) return result(DENIED, "execution_in_progress");
            if (toolRunning) return result(DENIED, "worker_unavailable");
            if (calls >= budget.maxCalls()) return result(DENIED, "call_budget");
            if (outputCharacters >= budget.maxOutputCharacters()) return result(DENIED, "output_budget");
            calls++;
            output = new BoundedOutput(callStarted);
            toolRunning = true;
            try {
                future = worker.submit(() -> {
                    try {
                        return invoke(tool, invocation, output, callStarted);
                    } finally {
                        synchronized (ConstrainedToolExecutor.this) {
                            toolRunning = false;
                        }
                    }
                });
            } catch (RejectedExecutionException rejected) {
                toolRunning = false;
                return result(DENIED, "worker_unavailable");
            }
            active = future;
            busy = true;
        }
        try {
            while (true) {
                ToolExecution.Result stopped = stopped(callStarted);
                if (stopped != null) return stopped;
                try {
                    ToolExecution.Result completed = future.get(Math.min(remaining(callStarted),
                            TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS);
                    stopped = stopped(callStarted);
                    return stopped == null ? completed : stopped;
                } catch (TimeoutException waiting) {
                    continue;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(CANCELLED, "caller_interrupted");
        } catch (CancellationException cancelled) {
            return result(CANCELLED, "cancelled");
        } catch (ExecutionException failed) {
            ToolExecution.Result stopped = stopped(callStarted);
            if (stopped != null) return stopped;
            if (failed.getCause() instanceof CancellationException || failed.getCause() instanceof InterruptedException) {
                return result(CANCELLED, "tool_cancelled");
            }
            return result(FAILED, "tool_failed");
        } finally {
            output.seal();
            future.cancel(true);
            synchronized (this) {
                active = null;
                busy = false;
            }
        }
    }

    private <I> ToolExecution.Result invoke(Registered<I> registered, ToolExecution.Invocation<?> invocation,
                                             BoundedOutput output, long callStarted) throws Exception {
        ToolExecution.Context context = new ToolExecution.Context(binding,
                () -> stopped(callStarted) != null);
        context.checkpoint();
        I input = registered.inputType().cast(invocation.input());
        String fingerprint = Objects.requireNonNull(registered.tool().invocationKey(input));
        context.checkpoint();
        synchronized (this) {
            if (!seen.add(new Key(invocation.toolId(), fingerprint))) return result(DENIED, "duplicate_invocation");
        }
        registered.tool().execute(input, context, output);
        context.checkpoint();
        return output.finish();
    }

    private long remaining(long callStarted) {
        long now = System.nanoTime();
        return Math.min(budget.totalTime().toNanos() - (now - started),
                budget.perCallTime().toNanos() - (now - callStarted));
    }

    private ToolExecution.Result stopped(long callStarted) {
        if (closed || cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            return result(CANCELLED, "cancelled");
        }
        return remaining(callStarted) <= 0 ? result(TIMEOUT, "time_budget") : null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (active != null) active.cancel(true);
        worker.shutdownNow();
    }

    private static ToolExecution.Result result(ToolExecution.Status status, String reason) {
        return new ToolExecution.Result(status, "", false, reason);
    }

    private static <I> Registered<I> register(ReadOnlyTool<I> tool) {
        return new Registered<>(Objects.requireNonNull(tool.inputType()), Set.copyOf(tool.bindings()), tool);
    }

    private record Registered<I>(Class<I> inputType, Set<ToolExecution.Binding> bindings, ReadOnlyTool<I> tool) {}

    private record Key(String toolId, String fingerprint) {}

    private final class BoundedOutput implements ToolExecution.Output {
        private final StringBuilder text = new StringBuilder();
        private final long callStarted;
        private boolean truncated;
        private boolean sealed;

        private BoundedOutput(long callStarted) { this.callStarted = callStarted; }

        @Override
        public synchronized void append(String value) {
            Objects.requireNonNull(value);
            if (sealed || stopped(callStarted) != null) throw new CancellationException();
            synchronized (ConstrainedToolExecutor.this) {
                int available = Math.min(budget.maxCharactersPerCall() - text.length(),
                        budget.maxOutputCharacters() - outputCharacters);
                int accepted = Math.min(available, value.length());
                text.append(value, 0, accepted);
                outputCharacters += accepted;
                truncated |= accepted < value.length();
            }
        }

        private synchronized ToolExecution.Result finish() {
            sealed = true;
            return new ToolExecution.Result(text.isEmpty() ? EMPTY : OK, text.toString(), truncated, "");
        }

        private synchronized void seal() { sealed = true; }
    }
}
