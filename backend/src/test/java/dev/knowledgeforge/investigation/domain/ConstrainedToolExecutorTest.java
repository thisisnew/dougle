package dev.knowledgeforge.investigation.domain;

import static dev.knowledgeforge.investigation.domain.ToolExecution.Status.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ConstrainedToolExecutorTest {
    private static final ToolExecution.Binding BINDING = new ToolExecution.Binding("workspace", "test", "documents");
    private static final ToolExecution.Budget BUDGET = budget(10, 5000, 1000, 100, 100);

    @Test
    void typedReadReceivesExactBindingAndReturnsOk() {
        ReadOnlyTool<Query> tool = tool((input, context, output) -> {
            assertEquals(BINDING, context.binding());
            context.checkpoint();
            output.append(input.value());
        });
        try (var executor = executor(tool, BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("read", "fictional document"));
            assertEquals(OK, result.status());
            assertEquals("fictional document", result.output());
            assertFalse(result.truncated());
        }
    }

    @Test
    void emptyIsDistinctFromFailedAndDenied() {
        try (var executor = executor(tool((input, context, output) -> {}), BUDGET, new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(invocation("read", "empty")).status());
            assertEquals(DENIED, executor.execute(invocation("unknown", "empty")).status());
        }
        try (var executor = executor(tool((input, context, output) -> {
            output.append("private partial data");
            throw new IllegalStateException("credential must not escape");
        }), BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("read", "failure"));
            assertEquals(FAILED, result.status());
            assertEquals("", result.output());
            assertEquals("tool_failed", result.reason());
            assertFalse(result.toString().contains("credential"));
        }
    }

    @Test
    void unknownShellAndUrlNamesNeverExecute() {
        AtomicInteger invoked = new AtomicInteger();
        try (var executor = executor(tool((input, context, output) -> invoked.incrementAndGet()), BUDGET,
                new ToolExecution.Cancellation())) {
            for (String id : List.of("unknown", "shell", "curl", "https://example.invalid", "sh -c command")) {
                assertEquals("unknown_tool", executor.execute(invocation(id, "query")).reason());
            }
            assertEquals(0, invoked.get());
        }
    }

    @Test
    void workspaceEnvironmentAndScopeMustAllMatchSession() {
        AtomicInteger invoked = new AtomicInteger();
        try (var executor = executor(tool((input, context, output) -> invoked.incrementAndGet()), BUDGET,
                new ToolExecution.Cancellation())) {
            for (var other : List.of(new ToolExecution.Binding("other", "test", "documents"),
                    new ToolExecution.Binding("workspace", "production", "documents"),
                    new ToolExecution.Binding("workspace", "test", "*"))) {
                assertEquals("binding_mismatch", executor.execute(
                        new ToolExecution.Invocation<>("read", other, new Query("query"))).reason());
            }
            assertEquals(0, invoked.get());
        }
    }

    @Test
    void registeredToolMustExplicitlyAllowSessionBinding() {
        var other = new ToolExecution.Binding("workspace", "test", "other");
        try (var executor = new ConstrainedToolExecutor(List.of(tool((input, context, output) -> fail())),
                other, BUDGET, new ToolExecution.Cancellation())) {
            assertEquals("scope_denied", executor.execute(
                    new ToolExecution.Invocation<>("read", other, new Query("query"))).reason());
        }
    }

    @Test
    void wrongInputTypeIsDeniedBeforeToolRuns() {
        try (var executor = executor(tool((input, context, output) -> fail()), BUDGET, new ToolExecution.Cancellation())) {
            assertEquals("input_type", executor.execute(new ToolExecution.Invocation<>("read", BINDING, 17)).reason());
        }
    }

    @Test
    void identicalAndCanonicalEquivalentInvocationsAreRejected() {
        AtomicInteger invoked = new AtomicInteger();
        try (var executor = executor(tool((input, context, output) -> invoked.incrementAndGet()), BUDGET,
                new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(invocation("read", "query")).status());
            assertEquals("duplicate_invocation", executor.execute(invocation("read", "query")).reason());
            assertEquals("duplicate_invocation", executor.execute(invocation("read", " query ")).reason());
            assertEquals(EMPTY, executor.execute(invocation("read", "different")).status());
            assertEquals(2, invoked.get());
        }
    }

    @Test
    void failedInvocationCannotBeReplayed() {
        try (var executor = executor(tool((input, context, output) -> { throw new Exception(); }), BUDGET,
                new ToolExecution.Cancellation())) {
            assertEquals(FAILED, executor.execute(invocation("read", "query")).status());
            assertEquals("duplicate_invocation", executor.execute(invocation("read", "query")).reason());
        }
    }

    @Test
    void callsIncludingDuplicatesConsumeAdmissionBudget() {
        try (var executor = executor(tool((input, context, output) -> {}), budget(2, 5000, 1000, 100, 100),
                new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(invocation("read", "query")).status());
            assertEquals("duplicate_invocation", executor.execute(invocation("read", "query")).reason());
            assertEquals("call_budget", executor.execute(invocation("read", "different")).reason());
        }
    }

    @Test
    void perCallOutputIsBoundedAcrossAppendsAndMarkedTruncated() {
        try (var executor = executor(tool((input, context, output) -> {
            output.append("abc");
            output.append("defghijkl");
            output.append("more");
        }), budget(2, 5000, 1000, 100, 5), new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("read", "query"));
            assertEquals(OK, result.status());
            assertEquals("abcde", result.output());
            assertTrue(result.truncated());
        }
    }

    @Test
    void exactOutputLimitIsNotTruncated() {
        try (var executor = executor(tool((input, context, output) -> output.append("12345")),
                budget(2, 5000, 1000, 5, 5), new ToolExecution.Cancellation())) {
            assertFalse(executor.execute(invocation("read", "query")).truncated());
            assertEquals("output_budget", executor.execute(invocation("read", "other")).reason());
        }
    }

    @Test
    void cumulativeOutputBudgetAppliesAcrossCalls() {
        try (var executor = executor(tool((input, context, output) -> output.append("1234")),
                budget(5, 5000, 1000, 6, 5), new ToolExecution.Cancellation())) {
            assertEquals("1234", executor.execute(invocation("read", "first")).output());
            var second = executor.execute(invocation("read", "second"));
            assertEquals("12", second.output());
            assertTrue(second.truncated());
            assertEquals("output_budget", executor.execute(invocation("read", "third")).reason());
        }
    }

    @Test
    void discardedFailureOutputStillConsumesBudget() {
        try (var executor = executor(tool((input, context, output) -> {
            output.append("12345");
            throw new Exception();
        }), budget(3, 5000, 1000, 5, 5), new ToolExecution.Cancellation())) {
            assertEquals(FAILED, executor.execute(invocation("read", "first")).status());
            assertEquals("output_budget", executor.execute(invocation("read", "second")).reason());
        }
    }

    @Test
    void cancellationBeforeExecutionDoesNotInvokeTool() {
        var cancellation = new ToolExecution.Cancellation();
        cancellation.cancel();
        try (var executor = executor(tool((input, context, output) -> fail()), BUDGET, cancellation)) {
            assertEquals(CANCELLED, executor.execute(invocation("read", "query")).status());
        }
    }

    @Test
    void cancellationAfterToolWorkDiscardsOutput() {
        var cancellation = new ToolExecution.Cancellation();
        try (var executor = executor(tool((input, context, output) -> {
            output.append("private partial");
            cancellation.cancel();
        }), BUDGET, cancellation)) {
            var result = executor.execute(invocation("read", "query"));
            assertEquals(CANCELLED, result.status());
            assertEquals("", result.output());
        }
    }

    @Test
    void perCallTimeoutInterruptsWorkerAndDoesNotLeakOutputOrThread() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var thread = new AtomicReference<Thread>();
        var retained = new AtomicReference<ToolExecution.Output>();
        try (var executor = executor(tool((input, context, output) -> {
            thread.set(Thread.currentThread());
            retained.set(output);
            output.append("partial");
            entered.countDown();
            try { new CountDownLatch(1).await(); } finally { exited.countDown(); }
        }), budget(5, 5000, 150, 100, 100), new ToolExecution.Cancellation())) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("read", "query")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var result = future.get(2, TimeUnit.SECONDS);
            assertEquals(TIMEOUT, result.status());
            assertEquals("", result.output());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> retained.get().append("late"));
        }
        thread.get().join(2000);
        assertFalse(thread.get().isAlive());
    }

    @Test
    void totalTimeExpiresEvenBeforeFirstCall() throws Exception {
        try (var executor = executor(tool((input, context, output) -> fail()), budget(2, 30, 1000, 100, 100),
                new ToolExecution.Cancellation())) {
            Thread.sleep(60);
            assertEquals(TIMEOUT, executor.execute(invocation("read", "query")).status());
        }
    }

    @Test
    void totalTimeCapsLongerPerCallTimeout() {
        try (var executor = executor(tool((input, context, output) -> new CountDownLatch(1).await()),
                budget(2, 100, 5000, 100, 100), new ToolExecution.Cancellation())) {
            assertTimeout(Duration.ofSeconds(2), () -> assertEquals(TIMEOUT,
                    executor.execute(invocation("read", "query")).status()));
        }
    }

    @Test
    void cancellationDuringWorkInterruptsAndCleansUp() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var cancellation = new ToolExecution.Cancellation();
        try (var executor = executor(tool((input, context, output) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } finally { exited.countDown(); }
        }), BUDGET, cancellation)) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("read", "query")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancellation.cancel();
            assertEquals(CANCELLED, future.get(2, TimeUnit.SECONDS).status());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeDuringWorkCancelsAndRejectsFutureCalls() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try (var executor = executor(tool((input, context, output) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } finally { exited.countDown(); }
        }), BUDGET, new ToolExecution.Cancellation())) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("read", "query")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            executor.close();
            assertEquals(CANCELLED, future.get(2, TimeUnit.SECONDS).status());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(CANCELLED, executor.execute(invocation("read", "other")).status());
        }
    }

    @Test
    void concurrentCallsAreDeniedWithoutQueuing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = executor(tool((input, context, output) -> {
            entered.countDown();
            release.await();
        }), BUDGET, new ToolExecution.Cancellation())) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("read", "first")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("execution_in_progress", executor.execute(invocation("read", "second")).reason());
            release.countDown();
            assertEquals(EMPTY, future.get(2, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
        }
    }

    @Test
    void uncooperativeTimedOutToolCannotSpawnReplacementWorkers() throws Exception {
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        AtomicInteger invoked = new AtomicInteger();
        var thread = new AtomicReference<Thread>();
        try (var executor = executor(tool((input, context, output) -> {
            invoked.incrementAndGet();
            thread.set(Thread.currentThread());
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            } finally {
                exited.countDown();
            }
        }), budget(5, 5000, 150, 100, 100), new ToolExecution.Cancellation())) {
            var future = CompletableFuture.supplyAsync(() -> executor.execute(invocation("read", "first")));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(TIMEOUT, future.get(2, TimeUnit.SECONDS).status());
            assertEquals("worker_unavailable", executor.execute(invocation("read", "second")).reason());
            assertEquals(1, invoked.get());
        } finally {
            release.countDown();
        }
        assertTrue(exited.await(2, TimeUnit.SECONDS));
        thread.get().join(2000);
        assertFalse(thread.get().isAlive());
    }

    @Test
    void callerInterruptionIsPreservedAndWorkerIsInterrupted() throws Exception {
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var result = new AtomicReference<ToolExecution.Result>();
        var interrupted = new AtomicReference<Boolean>();
        try (var executor = executor(tool((input, context, output) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } finally { exited.countDown(); }
        }), BUDGET, new ToolExecution.Cancellation())) {
            Thread caller = new Thread(() -> {
                result.set(executor.execute(invocation("read", "query")));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertEquals(CANCELLED, result.get().status());
            assertTrue(interrupted.get());
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void successfulOutputCannotBeWrittenAfterReturn() {
        var retained = new AtomicReference<ToolExecution.Output>();
        try (var executor = executor(tool((input, context, output) -> {
            retained.set(output);
            output.append("result");
        }), BUDGET, new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("read", "query"));
            assertThrows(CancellationException.class, () -> retained.get().append("late data"));
            assertEquals("result", result.output());
        }
    }

    @Test
    void fingerprintWorkIsAlsoTimeBounded() {
        var tool = new ReadOnlyTool<Query>() {
            public String id() { return "read"; }
            public Class<Query> inputType() { return Query.class; }
            public Set<ToolExecution.Binding> bindings() { return Set.of(BINDING); }
            public String invocationKey(Query input) {
                try { new CountDownLatch(1).await(); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return input.value();
            }
            public void execute(Query input, ToolExecution.Context context, ToolExecution.Output output) { fail(); }
        };
        try (var executor = executor(tool, budget(2, 5000, 100, 100, 100), new ToolExecution.Cancellation())) {
            assertEquals(TIMEOUT, executor.execute(invocation("read", "query")).status());
        }
    }

    @Test
    void registryAndBindingsAreFrozenAtConstruction() {
        var bindings = new HashSet<>(Set.of(BINDING));
        var tool = new ReadOnlyTool<Query>() {
            public String id() { return "read"; }
            public Class<Query> inputType() { return Query.class; }
            public Set<ToolExecution.Binding> bindings() { return bindings; }
            public String invocationKey(Query input) { return input.value(); }
            public void execute(Query input, ToolExecution.Context context, ToolExecution.Output output) { }
        };
        var tools = new ArrayList<ReadOnlyTool<?>>();
        tools.add(tool);
        try (var executor = new ConstrainedToolExecutor(tools, BINDING, BUDGET, new ToolExecution.Cancellation())) {
            tools.clear();
            bindings.clear();
            assertEquals(EMPTY, executor.execute(invocation("read", "query")).status());
        }
    }

    @Test
    void duplicateRegistrationsAreRejected() {
        var tool = tool((input, context, output) -> {});
        assertThrows(IllegalArgumentException.class, () -> new ConstrainedToolExecutor(List.of(tool, tool),
                BINDING, BUDGET, new ToolExecution.Cancellation()));
    }

    @Test
    void toolCancellationAndInterruptionAreDistinctFromFailure() {
        for (Exception cause : List.of(new CancellationException(), new InterruptedException())) {
            try (var executor = executor(tool((input, context, output) -> { throw cause; }), BUDGET,
                    new ToolExecution.Cancellation())) {
                var result = executor.execute(invocation("read", "query"));
                assertEquals(CANCELLED, result.status());
                assertEquals("", result.output());
                assertEquals("tool_cancelled", result.reason());
            }
        }
    }

    @Test
    void repeatedSequentialCallsDoNotRaceWithWorkerCompletion() {
        try (var executor = executor(tool((input, context, output) -> output.append("x")),
                budget(200, 5000, 1000, 200, 1), new ToolExecution.Cancellation())) {
            for (int index = 0; index < 200; index++) {
                assertEquals(OK, executor.execute(invocation("read", "query" + index)).status());
            }
            assertEquals("call_budget", executor.execute(invocation("read", "last")).reason());
        }
    }

    @Test
    void largeStreamRetainsOnlyBudgetedOutput() {
        String chunk = "x".repeat(1000);
        try (var executor = executor(tool((input, context, output) -> {
            for (int index = 0; index < 10000; index++) output.append(chunk);
        }), budget(2, 5000, 2000, 10, 10), new ToolExecution.Cancellation())) {
            var result = executor.execute(invocation("read", "query"));
            assertEquals(OK, result.status());
            assertEquals("x".repeat(10), result.output());
            assertTrue(result.truncated());
        }
    }

    @Test
    void sameArgumentsOnDifferentRegisteredToolsAreNotDuplicates() {
        var second = new ReadOnlyTool<Query>() {
            public String id() { return "other"; }
            public Class<Query> inputType() { return Query.class; }
            public Set<ToolExecution.Binding> bindings() { return Set.of(BINDING); }
            public String invocationKey(Query input) { return input.value(); }
            public void execute(Query input, ToolExecution.Context context, ToolExecution.Output output) { }
        };
        try (var executor = new ConstrainedToolExecutor(List.of(tool((input, context, output) -> {}), second),
                BINDING, BUDGET, new ToolExecution.Cancellation())) {
            assertEquals(EMPTY, executor.execute(invocation("read", "query")).status());
            assertEquals(EMPTY, executor.execute(invocation("other", "query")).status());
        }
    }

    @Test
    void cancellationInFingerprintPreventsToolExecution() {
        var cancellation = new ToolExecution.Cancellation();
        var tool = new ReadOnlyTool<Query>() {
            public String id() { return "read"; }
            public Class<Query> inputType() { return Query.class; }
            public Set<ToolExecution.Binding> bindings() { return Set.of(BINDING); }
            public String invocationKey(Query input) {
                cancellation.cancel();
                return input.value();
            }
            public void execute(Query input, ToolExecution.Context context, ToolExecution.Output output) { fail(); }
        };
        try (var executor = executor(tool, BUDGET, cancellation)) {
            assertEquals(CANCELLED, executor.execute(invocation("read", "query")).status());
        }
    }

    @Test
    void invalidBudgetsAndBindingsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> budget(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> budget(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> budget(1, 1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> budget(1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> budget(1, 1, 1, 1, 0));
        assertThrows(ArithmeticException.class, () -> new ToolExecution.Budget(1, Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(1), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ToolExecution.Binding("", "test", "documents"));
        assertThrows(IllegalArgumentException.class, () -> new ToolExecution.Binding("workspace", " ", "documents"));
        assertThrows(IllegalArgumentException.class, () -> new ToolExecution.Binding("workspace", "test", null));
    }

    private static ConstrainedToolExecutor executor(ReadOnlyTool<?> tool, ToolExecution.Budget budget,
                                                     ToolExecution.Cancellation cancellation) {
        return new ConstrainedToolExecutor(List.of(tool), BINDING, budget, cancellation);
    }

    private static ToolExecution.Budget budget(int calls, long totalMillis, long callMillis, int totalOutput, int callOutput) {
        return new ToolExecution.Budget(calls, Duration.ofMillis(totalMillis), Duration.ofMillis(callMillis), totalOutput, callOutput);
    }

    private static ToolExecution.Invocation<Query> invocation(String id, String value) {
        return new ToolExecution.Invocation<>(id, BINDING, new Query(value));
    }

    private static ReadOnlyTool<Query> tool(Body body) {
        return new ReadOnlyTool<>() {
            public String id() { return "read"; }
            public Class<Query> inputType() { return Query.class; }
            public Set<ToolExecution.Binding> bindings() { return Set.of(BINDING); }
            public String invocationKey(Query input) { return input.value().strip(); }
            public void execute(Query input, ToolExecution.Context context, ToolExecution.Output output) throws Exception {
                body.run(input, context, output);
            }
        };
    }

    private record Query(String value) {}

    @FunctionalInterface
    private interface Body {
        void run(Query input, ToolExecution.Context context, ToolExecution.Output output) throws Exception;
    }
}
