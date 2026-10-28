package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.ToolExecution;

public interface BrowserInvestigationTools {
    String TOOL_ID = "browser.rendered.capture";
    static ToolExecution.Binding binding(String workspace, BrowserCaptureScope scope) {
        return new ToolExecution.Binding(workspace, "isolated-browser", scope.origin());
    }
    interface Session extends AutoCloseable {
        ToolExecution.Result execute(ToolExecution.Invocation<?> invocation);
        void close();
    }
    Session open(String workspace, BrowserCaptureScope scope, ToolExecution.Cancellation cancellation);
}
