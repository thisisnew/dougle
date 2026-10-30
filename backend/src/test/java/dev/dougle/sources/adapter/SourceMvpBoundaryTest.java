package dev.dougle.sources.adapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.application.SourceService;
import dev.dougle.sources.domain.Source;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceMvpBoundaryTest {
    private final SourceService sources = mock(SourceService.class);
    private final SearchService search = mock(SearchService.class);
    private final SourceController controller = new SourceController(sources, search, org.mockito.Mockito.mock(dev.dougle.sources.application.SourceSyncJobs.class));

    private Source archived(String kind) {
        return new Source("saved", "workspace", "Saved evidence", kind, "https://example.org", "", "READY", "revision", null,
                null, "root", true, 60, 0L, 0, 0, null);
    }
    @ParameterizedTest @ValueSource(strings = {"URL", "GITHUB", "url", "github", "UNKNOWN"})
    void rejectsRegistrationBeforeCollection(String kind) {
        assertEquals(400, assertThrows(LibraryException.class, () -> controller.create("workspace",
                new SourceController.Registration("Source", kind, "https://github.com/company/repo", "", true))).status());
        verifyNoInteractions(sources, search);
    }
    @ParameterizedTest @ValueSource(strings = {"GITHUB"})
    void preservesArchiveAndRejectsResyncAndPolling(String kind) {
        var saved = archived(kind);
        when(sources.list("workspace")).thenReturn(List.of(saved));
        assertEquals(List.of(saved), controller.list("workspace"));
        assertEquals(400, assertThrows(LibraryException.class, () -> controller.sync("workspace", "saved")).status());
        assertEquals(400, assertThrows(LibraryException.class, () -> controller.polling("workspace", "saved",
                new SourceController.PollingSettings(true, 60, 0))).status());
        verify(sources, never()).sync(anyString(), anyString(), any());
        verify(sources, never()).delete(anyString(), anyString());
        verifyNoInteractions(search);
        controller.polling("workspace", "saved", new SourceController.PollingSettings(false, 60, 0));
        verify(sources).configurePolling("workspace", "saved", false, 60, 0);
    }
    @ParameterizedTest @ValueSource(strings = {"LOCAL_FOLDER", "MYSQL"})
    void keepsSupportedRegistration(String kind) {
        controller.create("workspace", new SourceController.Registration("Source", kind, "/synthetic", "", false));
        verify(sources).create("workspace", "Source", kind, "/synthetic", "");
    }
    @Test void doesNotFindSourcesFromAnotherWorkspace() {
        when(sources.list("other")).thenReturn(List.of());
        assertEquals(404, assertThrows(LibraryException.class, () -> controller.sync("other", "saved")).status());
        verify(sources, never()).sync(anyString(), anyString(), any());
    }
}
