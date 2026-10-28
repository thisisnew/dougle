package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.BrowserCaptureScope;
import dev.dougle.investigation.application.BrowserCaptureTransport;
import dev.dougle.investigation.application.BrowserInvestigationTools;
import dev.dougle.investigation.domain.ConstrainedToolExecutor;
import dev.dougle.investigation.domain.ToolExecution;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.dougle.investigation.domain.ReadOnlyTool;
import org.springframework.stereotype.Component;

@Component
public class BrowserToolExecutorFactory implements BrowserInvestigationTools {
    private final BrowserCaptureTransport transport;
    public BrowserToolExecutorFactory(BrowserCaptureTransport transport) { this.transport = transport; }

    public Session open(String workspace, BrowserCaptureScope scope, ToolExecution.Cancellation cancellation) {
        var binding = BrowserInvestigationTools.binding(workspace, scope);
        var tool = new BrowserCaptureTool(transport, binding, scope);
        var started = new AtomicBoolean();
        var completed = new CountDownLatch(1);
        ReadOnlyTool<BrowserCaptureScope> tracked = new ReadOnlyTool<>() {
            public String id() { return tool.id(); }
            public Class<BrowserCaptureScope> inputType() { return tool.inputType(); }
            public Set<ToolExecution.Binding> bindings() { return tool.bindings(); }
            public String invocationKey(BrowserCaptureScope input) { return tool.invocationKey(input); }
            public void execute(BrowserCaptureScope input, ToolExecution.Context context, ToolExecution.Output output) throws Exception {
                started.set(true);
                try { tool.execute(input, context, output); }
                finally { completed.countDown(); }
            }
        };
        var executor = new ConstrainedToolExecutor(List.of(tracked), binding,
                new ToolExecution.Budget(1, Duration.ofSeconds(32), Duration.ofSeconds(30), 65536, 65536), cancellation);
        return new Session() {
            public ToolExecution.Result execute(ToolExecution.Invocation<?> invocation) { return executor.execute(invocation); }
            public void close() {
                executor.close();
                boolean interrupted = Thread.interrupted();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
                try {
                    while (started.get() && completed.getCount() != 0 && System.nanoTime() < deadline) {
                        try { completed.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException cancelled) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        };
    }
}
