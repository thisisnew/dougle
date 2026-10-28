package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.LocalSearchQuery;
import dev.dougle.investigation.domain.ReadOnlyTool;
import dev.dougle.investigation.domain.ToolExecution;
import dev.dougle.library.application.LibraryStore;
import dev.dougle.search.application.SearchService;
import dev.dougle.search.application.EvidenceSourceStore;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LocalWorkspaceSearchTool implements ReadOnlyTool<LocalSearchQuery> {
    public static final String ID = "local.workspace.search";
    private final SearchService search;
    private final LibraryStore library;
    private final ToolExecution.Binding binding;
    private final EvidenceSourceStore sources;
    private final java.util.function.Consumer<List<dev.dougle.search.application.LocalSearchSnapshot.Entry>> snapshot;

    public LocalWorkspaceSearchTool(SearchService search, LibraryStore library, String workspaceId) {
        this(search, library, workspaceId, (EvidenceSourceStore) null, null);
    }

    LocalWorkspaceSearchTool(SearchService search, LibraryStore library, String workspaceId,
                            dev.dougle.knowledge.application.KnowledgeStore knowledge,
                            java.util.function.Consumer<List<dev.dougle.search.application.LocalSearchSnapshot.Entry>> snapshot) {
        this(search, library, workspaceId, EvidenceSourceStore.legacy(knowledge), snapshot);
    }

    LocalWorkspaceSearchTool(SearchService search, LibraryStore library, String workspaceId,
                            EvidenceSourceStore sources,
                            java.util.function.Consumer<List<dev.dougle.search.application.LocalSearchSnapshot.Entry>> snapshot) {
        this.search = Objects.requireNonNull(search);
        this.library = Objects.requireNonNull(library);
        binding = binding(workspaceId);
        this.sources = sources;
        this.snapshot = snapshot;
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
        List<dev.dougle.search.application.LocalSearchSnapshot.Entry> inspected = null;
        if (snapshot != null) {
            inspected = inspected(hits, context);
            hits = inspected.stream().map(dev.dougle.search.application.LocalSearchSnapshot.Entry::hit).toList();
        }
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
        if (snapshot != null) snapshot.accept(inspected);
    }

    private List<dev.dougle.search.application.LocalSearchSnapshot.Entry> inspected(
            List<dev.dougle.search.application.SearchIndex.Hit> hits, ToolExecution.Context context) {
        var result = new java.util.ArrayList<dev.dougle.search.application.LocalSearchSnapshot.Entry>();
        var identifiers = new java.util.HashSet<String>();
        int characters = 0;
        for (var hit : hits) {
            context.checkpoint();
            if (!"ORIGINAL".equals(hit.kind()) || !hit.bodyMatch()) continue;
            if (!identifiers.add(hit.entryId())) continue;
            if (result.size() >= 5) break;
            var entry = sources.withMaterial(binding.workspace(), hit.entryId(), found -> inspect(hit, found.orElseThrow(), context));
            characters += entry.hit().snippet().length();
            if (characters > 12000) throw new IllegalStateException("Snapshot evidence budget");
            result.add(entry);
        }
        return List.copyOf(result);
    }

    private dev.dougle.search.application.LocalSearchSnapshot.Entry inspect(
            dev.dougle.search.application.SearchIndex.Hit hit,
            dev.dougle.search.domain.EvidenceMaterial material, ToolExecution.Context context) {
        context.checkpoint();
        if (!List.of("READY", "PARTIAL").contains(material.analysisStatus())
                || !material.textHash().equals(hit.textHash())) {
            throw new IllegalStateException("Search evidence changed");
        }
        if (hit.snippet() == null) throw new IllegalStateException("Invalid evidence window");
        int end = Math.min(768, hit.snippet().length());
        if (end < hit.snippet().length() && Character.isLowSurrogate(hit.snippet().charAt(end))) end--;
        String excerpt = hit.snippet().substring(0, end);
        int endLine = hit.startLine() + (int) excerpt.chars().limit(Math.max(0, excerpt.length() - 1))
                .filter(value -> value == '\n').count();
        var window = material.window(excerpt, hit.startLine(), endLine, 768, true);
        if (window == null || hit.endLine() < endLine) {
            throw new IllegalStateException("Invalid evidence window");
        }
        context.checkpoint();
        var frozen = new dev.dougle.search.application.SearchIndex.Hit(hit.entryId(), "", null, "", null,
                window.text(), 0, window.startLine(), window.endLine(), true, "ORIGINAL", null, null,
                List.of(), "CURRENT", hit.textHash());
        return new dev.dougle.search.application.LocalSearchSnapshot.Entry(frozen, material.contentHash());
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
