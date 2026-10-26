package dev.knowledgeforge.security.application;

import dev.knowledgeforge.conversation.application.AnswerJobStore;
import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.ingestion.application.AnalysisStore;
import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryStore;
import dev.knowledgeforge.sources.application.SourceStore;
import dev.knowledgeforge.transfer.application.*;
import dev.knowledgeforge.transfer.domain.TransferArchiveCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialArchiveTest {
    final LibraryStore library = mock(LibraryStore.class);
    final DocumentAnalyzer analyzer = mock(DocumentAnalyzer.class);
    final WorkspaceArchivePersistence persistence = mock(WorkspaceArchivePersistence.class);
    final WorkspaceArchiveReceipts receipts = mock(WorkspaceArchiveReceipts.class);
    final WorkspaceArchiveService service = new WorkspaceArchiveService(library, mock(SourceStore.class),
            mock(ChatThreadService.class), mock(AnswerJobStore.class), persistence, receipts, analyzer,
            mock(AnalysisStore.class), mock(PlatformTransactionManager.class), Clock.systemUTC());

    byte[] archive(String name, String text) throws Exception {
        UUID key = UUID.randomUUID();
        var catalog = new WorkspaceArchiveData(1, name, List.of(new WorkspaceArchiveData.Material(
                key, null, "fixture.txt", "FILE", "2026-10-26T00:00:00Z")), List.of());
        return new TransferArchiveCodec().write(Map.of(WorkspaceArchiveService.CATALOG_KEY,
                JsonMapper.builder().build().writeValueAsBytes(catalog), key, text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void importRejectsRawSecretAndCatalogSecretBeforeAnalysisOrPersistence() throws Exception {
        for (var bytes : List.of(archive("safe", "password=Synthet1c-Only-Value-7823"),
                archive("xoxb-synthetic-secret", "safe"))) {
            byte[] original = bytes.clone();
            assertThatThrownBy(() -> service.preview(bytes, "new workspace"))
                    .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
            assertThat(bytes).isEqualTo(original);
        }
        verifyNoInteractions(analyzer, persistence, receipts);
    }

    @Test void extractedTextIsCheckedEvenWhenSourceBytesDoNotExposeSecret() throws Exception {
        when(analyzer.analyze(anyString(), any())).thenReturn(new Analysis(
                "api_key=Synthet1c-Only-Value-7823", "text/plain", "READY", null, List.of()));
        byte[] bytes = archive("safe", "opaque synthetic input");
        assertThatThrownBy(() -> service.preview(bytes, "new workspace"))
                .isInstanceOf(LibraryException.class).hasMessageContaining("인증정보");
        verifyNoInteractions(persistence, receipts);
    }

    @Test void safeCodePlaceholdersAreAcceptedUnchanged() throws Exception {
        String text = "const token = id; password=example; https://example.invalid; person@example.invalid";
        when(analyzer.analyze(anyString(), any())).thenReturn(new Analysis(text, "text/plain", "READY", null, List.of()));
        byte[] bytes = archive("safe", text);
        byte[] original = bytes.clone();
        assertThat(service.preview(bytes, "new workspace").materials()).isEqualTo(1);
        assertThat(bytes).isEqualTo(original);
        verifyNoInteractions(persistence, receipts);
    }
}
