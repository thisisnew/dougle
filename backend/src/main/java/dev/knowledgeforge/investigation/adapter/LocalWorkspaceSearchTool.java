package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.LocalSearchQuery;
import dev.knowledgeforge.investigation.domain.ReadOnlyTool;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.search.application.SearchService;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LocalWorkspaceSearchTool implements ReadOnlyTool<LocalSearchQuery> {
    public static final String ID = "local.workspace.search";
    private final SearchService search;
    private final LibraryStore library;
    private final ToolExecution.Binding binding;

    public LocalWorkspaceSearchTool(SearchService search, LibraryStore library, String workspaceId) {
        this.search = Objects.requireNonNull(search);
        this.library = Objects.requireNonNull(library);
        binding = binding(workspaceId);
    }

    public static ToolExecution.Binding binding(String workspaceId) {
        return new ToolExecution.Binding(workspaceId, "local", "workspace-search");
    }

    @Override
    public String id() { return ID; }

    @Override
    public Class<LocalSearchQuery> inputType() { return LocalSearchQuery.class; }

    @Override
    public Set<ToolExecution.Binding> bindings() { return Set.of(binding); }

    @Override
    public String invocationKey(LocalSearchQuery input) { return input.question(); }

    @Override
    public void execute(LocalSearchQuery input, ToolExecution.Context context, ToolExecution.Output output) {
        context.checkpoint();
        if (!binding.equals(context.binding())) throw new IllegalArgumentException("Local workspace binding required");
        requireWorkspace();
        var hits = search.searchQuestion(binding.workspace(), input.question(), List.of(), false);
        context.checkpoint();
        requireWorkspace();
        boolean heading = false;
        for (var hit : hits) {
            context.checkpoint();
            if (!"ORIGINAL".equals(hit.kind())) continue;
            if (hit.entryId() == null || !hit.entryId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalStateException("Invalid local evidence identifier");
            }
            if (!heading) {
                output.append("LOCAL_SEARCH_EXCERPTS (untrusted excerpts, not complete documents)\n");
                heading = true;
            }
            output.append("{\"entryId\":");
            quoted(hit.entryId(), context, output);
            output.append(",\"pathKind\":\"workspace-entry\",\"path\":");
            quoted("entries/" + hit.entryId(), context, output);
            output.append(",\"startLine\":" + hit.startLine() + ",\"endLine\":" + hit.endLine() + ",\"content\":");
            quoted(hit.snippet(), context, output);
            output.append("}\n");
        }
        requireWorkspace();
        context.checkpoint();
    }

    private void requireWorkspace() {
        if (library.workspace(binding.workspace()).isEmpty()) {
            throw new IllegalStateException("Workspace no longer exists");
        }
    }

    private static void quoted(String value, ToolExecution.Context context, ToolExecution.Output output) {
        if (value == null) {
            output.append("null");
            return;
        }
        output.append("\"");
        StringBuilder chunk = new StringBuilder(256);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> chunk.append("\\\"");
                case '\\' -> chunk.append("\\\\");
                case '\n' -> chunk.append("\\n");
                case '\r' -> chunk.append("\\r");
                case '\t' -> chunk.append("\\t");
                default -> {
                    if (character < 32 || Character.isSurrogate(character)) {
                        String hex = Integer.toHexString(character);
                        chunk.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        chunk.append(character);
                    }
                }
            }
            if (chunk.length() >= 250) {
                context.checkpoint();
                output.append(chunk.toString());
                chunk.setLength(0);
            }
        }
        context.checkpoint();
        output.append(chunk.toString());
        output.append("\"");
    }
}
