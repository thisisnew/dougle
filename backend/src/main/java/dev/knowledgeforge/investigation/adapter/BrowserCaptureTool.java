package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.BrowserCaptureScope;
import dev.knowledgeforge.investigation.application.BrowserCaptureTransport;
import dev.knowledgeforge.investigation.domain.ReadOnlyTool;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

public final class BrowserCaptureTool implements ReadOnlyTool<BrowserCaptureScope> {
    public static final String ID = "browser.rendered.capture";
    private final BrowserCaptureTransport transport;
    private final ToolExecution.Binding binding;
    private final BrowserCaptureScope approved;

    public BrowserCaptureTool(BrowserCaptureTransport transport, ToolExecution.Binding binding, BrowserCaptureScope approved) {
        this.transport = transport;
        this.binding = binding;
        this.approved = approved;
    }

    public String id() { return ID; }
    public Class<BrowserCaptureScope> inputType() { return BrowserCaptureScope.class; }
    public Set<ToolExecution.Binding> bindings() { return Set.of(binding); }
    public String invocationKey(BrowserCaptureScope input) { return input.toString(); }

    public void execute(BrowserCaptureScope input, ToolExecution.Context context, ToolExecution.Output output) throws Exception {
        context.checkpoint();
        if (!binding.equals(context.binding()) || !approved.equals(input)) throw new IllegalArgumentException("Unapproved browser scope");
        var snapshot = transport.capture(input, context);
        context.checkpoint();
        output.append(JsonMapper.builder().build().writeValueAsString(snapshot));
    }
}
