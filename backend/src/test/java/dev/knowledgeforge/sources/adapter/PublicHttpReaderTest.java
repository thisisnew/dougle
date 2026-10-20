package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import java.net.URI;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicHttpReaderTest {
    @Test
    @SuppressWarnings("unchecked")
    void redirectCannotBypassPrivateDestinationPolicy() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            var response = new BasicClassicHttpResponse(302);
            response.addHeader("Location", "https://127.0.0.1/private");
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.read(URI.create("https://example.org/start")))
                    .isInstanceOf(LibraryException.class).hasMessageContaining("공개 HTTPS");
            verify(client, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsRedirectLoopsAtTheConfiguredLimit() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            var response = new BasicClassicHttpResponse(302);
            response.addHeader("Location", "/again");
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.read(URI.create("https://example.org/start")))
                    .isInstanceOf(LibraryException.class).hasMessageContaining("리다이렉트 한도");
            verify(client, times(4)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        }
    }
}
