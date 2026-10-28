package dev.dougle.investigation.domain;

import java.util.Set;

public interface ReadOnlyTool<I> {
    String id();

    Class<I> inputType();

    Set<ToolExecution.Binding> bindings();

    String invocationKey(I input);

    void execute(I input, ToolExecution.Context context, ToolExecution.Output output) throws Exception;
}
