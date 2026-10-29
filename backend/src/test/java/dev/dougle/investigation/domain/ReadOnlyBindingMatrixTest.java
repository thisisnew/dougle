package dev.dougle.investigation.domain;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class ReadOnlyBindingMatrixTest {
    static Stream<Arguments> bindings() {
        return Stream.of("local.search", "source.read", "evidence.preview", "schema.read",
                "document.read", "snapshot.read", "browser.capture", "history.read")
                .flatMap(id -> Stream.of("team", "TEAM", "팀", "équipe", "e\u0301quipe", "a/b", "a:b", "a.b")
                        .flatMap(value -> Stream.of("workspace", "environment", "scope")
                                .map(axis -> Arguments.of(id, value, axis))));
    }

    @ParameterizedTest(name = "READONLY-{index}: tool={0} binding={1} mismatch={2}")
    @MethodSource("bindings")
    void mismatchedAuthorityNeverInvokesToolOrConsumesReadBudget(String id, String value, String axis) {
        var binding = new ToolExecution.Binding(value, "environment", "scope");
        var wrong = new ToolExecution.Binding(axis.equals("workspace") ? value + "/other" : value,
                axis.equals("environment") ? "environment/other" : "environment",
                axis.equals("scope") ? "scope/other" : "scope");
        var calls = new AtomicInteger();
        ReadOnlyTool<String> tool = new ReadOnlyTool<>() {
            public String id() { return id; }
            public Class<String> inputType() { return String.class; }
            public Set<ToolExecution.Binding> bindings() { return Set.of(binding); }
            public String invocationKey(String input) { return input; }
            public void execute(String input, ToolExecution.Context context, ToolExecution.Output output) {
                assertThat(context.binding()).isEqualTo(binding);
                calls.incrementAndGet();
                output.append("read:" + input);
            }
        };
        var budget = new ToolExecution.Budget(1, Duration.ofSeconds(30), Duration.ofSeconds(10), 512, 512);
        try (var executor = new ConstrainedToolExecutor(List.of(tool), binding, budget, new ToolExecution.Cancellation())) {
            var denied = executor.execute(new ToolExecution.Invocation<>(id, wrong, "synthetic"));
            assertThat(denied.status()).isEqualTo(ToolExecution.Status.DENIED);
            assertThat(denied.reason()).isEqualTo("binding_mismatch");
            assertThat(denied.output()).isEmpty();
            assertThat(calls.get()).isZero();
            var accepted = executor.execute(new ToolExecution.Invocation<>(id, binding, "synthetic"));
            assertThat(accepted.status()).isEqualTo(ToolExecution.Status.OK);
            assertThat(accepted.output()).isEqualTo("read:synthetic");
            assertThat(accepted.truncated()).isFalse();
            assertThat(calls.get()).isEqualTo(1);
            var exhausted = executor.execute(new ToolExecution.Invocation<>(id, binding, "different"));
            assertThat(exhausted.reason()).isEqualTo("call_budget");
            assertThat(calls.get()).isEqualTo(1);
        }
    }
}
