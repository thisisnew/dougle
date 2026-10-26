package dev.knowledgeforge;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.library.domain.Workspace;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.sources.domain.Source;
import dev.knowledgeforge.sources.domain.WorkspacePolling;
import dev.knowledgeforge.transfer.application.TeamSettingsService;
import dev.knowledgeforge.transfer.application.TeamSettingsService.Descriptor;
import dev.knowledgeforge.transfer.application.TeamSettingsService.Settings;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamSettingsServiceTest {
    private final LibraryStore library = mock(LibraryStore.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final TeamSettingsService service = new TeamSettingsService(library, sources);

    @Test
    void exportsOnlyDescriptorsWithoutPrivateLocationsOrInternalState() {
        when(library.workspace("workspace-id")).thenReturn(Optional.of(new Workspace("workspace-id", "Team", "private-date")));
        when(sources.workspacePolling("workspace-id")).thenReturn(new WorkspacePolling(true, 7, true, 3600));
        when(sources.list("workspace-id")).thenReturn(List.of(
                source("Folder", "LOCAL_FOLDER", "/Users/private/folder"),
                source("Database", "MYSQL", "mysql://user:secret@internal/db"),
                source("Web", "URL", "https://example.com/access/secret"),
                source("Repository", "GITHUB", "https://github.com/private/secret"),
                source("Unsafe", "URL", "https://user:secret@example.com/path?token=secret#private")));
        var result = service.exportSettings("workspace-id");
        String serialized = JsonMapper.builder().build().writeValueAsString(result);
        assertThat(serialized).doesNotContain("https://", "workspace-id", "source-id", "secret", "private", "internal", "root-id", "revision");
        assertThat(result.sources()).allMatch(descriptor -> descriptor.location() == null);
        assertThat(result.sources().get(0).location()).isNull();
        assertThat(result.sources().get(1).location()).isNull();
        assertThat(result.sources().get(3).location()).isNull();
        verify(library).workspace("workspace-id");
        verify(sources).list("workspace-id");
        verify(sources).workspacePolling("workspace-id");
        verifyNoMoreInteractions(library, sources);
    }

    @Test
    void previewReportsConflictsAndRequiredSetupWithoutMutationOrSourceReads() {
        when(library.workspaces()).thenReturn(List.of(new Workspace("hidden", "Team", "date")));
        var settings = new Settings(1, "Team", 60, List.of(new Descriptor("Folder", "LOCAL_FOLDER", null),
                new Descriptor("Web", "URL", null), new Descriptor("Web", "URL", null)));
        var result = service.preview(settings);
        assertThat(result.applySupported()).isTrue();
        assertThat(result.workspaceNameConflict()).isTrue();
        assertThat(result.entries().get(0).needsLocation()).isTrue();
        assertThat(result.entries().get(0).needsReconnection()).isFalse();
        assertThat(result.entries().get(1).needsReconnection()).isTrue();
        assertThat(result.entries().get(2).nameConflict()).isTrue();
        verify(library).workspaces();
        verifyNoMoreInteractions(library);
        verifyNoInteractions(sources);
    }

    @Test
    void rejectsUnsafeUrlsAndLocalLocationsBeforeReadingStores() {
        for (String location : List.of("https://example.com", "https://example.com/access/secret", "https://user:secret@example.com", "https://example.com?token=x", "https://example.com#token",
                "https://example.com/%74oken", "file:///Users/private", "/Users/private", "javascript:alert(1)", "https://example.com:99999")) {
            assertThatThrownBy(() -> service.preview(new Settings(1, "Team", 3600, List.of(new Descriptor("Web", "URL", location)))))
                    .isInstanceOf(LibraryException.class);
        }
        assertThatThrownBy(() -> service.preview(new Settings(1, "Team", 3600, List.of(new Descriptor("Folder", "LOCAL_FOLDER", "https://example.com")))))
                .isInstanceOf(LibraryException.class);
        verifyNoInteractions(library, sources);
    }

    @Test
    void enforcesVersionCountNamesAndIntervalLimits() {
        for (var settings : List.of(new Settings(2, "Team", 60, List.of()), new Settings(1, "Team", 59, List.of()),
                new Settings(1, "Team", 86401, List.of()), new Settings(1, "x".repeat(121), 60, List.of()),
                new Settings(1, "Team", 60, java.util.Collections.nCopies(101, new Descriptor("Web", "URL", null))),
                new Settings(1, "Team", 60, List.of(new Descriptor("x".repeat(121), "URL", null))),
                new Settings(1, "Team", 60, List.of(new Descriptor("Web", "UNKNOWN", null))))) {
            assertThatThrownBy(() -> service.preview(settings)).isInstanceOf(LibraryException.class);
        }
        TeamSettingsService.validate(new Settings(1, "x".repeat(20), 86400,
                java.util.Collections.nCopies(100, new Descriptor("x".repeat(120), "URL", null))));
        verifyNoInteractions(library, sources);
    }

    @Test
    void missingWorkspaceDoesNotReadSources() {
        when(library.workspace("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.exportSettings("missing")).isInstanceOf(LibraryException.class);
        verifyNoInteractions(sources);
    }

    @Test
    void preservesLegacyWorkspaceAndMaximumSourceNamesAndUsesCollectorKind() {
        String name = "가".repeat(120);
        when(library.workspace("workspace-id")).thenReturn(Optional.of(new Workspace("workspace-id", name, "date")));
        when(sources.workspacePolling("workspace-id")).thenReturn(new WorkspacePolling(false, 0, false, 3600));
        when(sources.list("workspace-id")).thenReturn(List.of(source(name, "LOCAL_FOLDER", "/private/folder")));
        when(library.workspaces()).thenReturn(List.of());
        var settings = service.exportSettings("workspace-id");
        assertThat(settings.workspaceName()).isEqualTo(name);
        assertThat(settings.sources().getFirst().name()).isEqualTo(name);
        assertThat(settings.sources().getFirst().kind()).isEqualTo(new dev.knowledgeforge.sources.adapter.LocalFolderCollector().kind());
        assertThat(service.preview(settings).needsWorkspaceRename()).isTrue();
        assertThatThrownBy(() -> TeamSettingsService.validate(new Settings(1, "Team", 60,
                List.of(new Descriptor("Folder", "FOLDER", null))))).isInstanceOf(LibraryException.class);
    }

    private Source source(String name, String kind, String location) {
        return new Source("source-id", "workspace-id", name, kind, location, "private-domain", "READY", "revision", "private-error",
                "private-time", "root-id", true, 3600, null, 0, 7, "private-date");
    }
}
