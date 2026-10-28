package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.BrowserCaptureScope;
import dev.dougle.investigation.application.BrowserCaptureTransport;
import dev.dougle.investigation.domain.ToolExecution;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserHttpGatewayTest {
    private final BrowserCaptureScope scope = new BrowserCaptureScope("https://example.com/app", List.of("/app"));
    private final ToolExecution.Context context = new ToolExecution.Context(
            new ToolExecution.Binding("fixture", "isolated-browser", scope.origin()), () -> false);

    @Test @SuppressWarnings("unchecked")
    void redirectsAndAuthenticationNeverForwardBodiesCredentialsOrFollowLocations() throws Exception {
        var client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(call -> {
            ClassicHttpRequest request = call.getArgument(0);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getFirstHeader("Authorization")).isNull();
            assertThat(request.getFirstHeader("Cookie")).isNull();
            assertThat(request.getFirstHeader("Referer")).isNull();
            var response = new BasicClassicHttpResponse(302);
            response.addHeader("Location", "https://127.0.0.1/private");
            response.addHeader("Set-Cookie", "secret=value");
            response.setEntity(new ByteArrayEntity("PRIVATE".getBytes(), ContentType.TEXT_PLAIN));
            HttpClientResponseHandler<Object> handler = call.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var gateway = new BrowserHttpGateway(client)) {
            var response = gateway.read(scope, scope.url(), context, deadline());
            assertThat(response.status()).isEqualTo(302);
            assertThat(response.body()).isEmpty();
            verify(client, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        }
    }

    @Test @SuppressWarnings("unchecked")
    void boundsEachResourceAndAggregateBytes() throws Exception {
        var client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(call -> {
            var response = new BasicClassicHttpResponse(200);
            response.setEntity(new ByteArrayEntity(new byte[BrowserCaptureTransport.MAX_RESPONSE_BYTES], ContentType.TEXT_PLAIN));
            HttpClientResponseHandler<Object> handler = call.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var gateway = new BrowserHttpGateway(client)) {
            for (int count = 0; count < 8; count++) assertThat(gateway.read(scope, scope.url(), context, deadline()).body()).hasSize(524288);
            assertThatThrownBy(() -> gateway.read(scope, scope.url(), context, deadline())).isInstanceOf(IOException.class);
        }
    }

    @Test void rejectsPrivateAndUnapprovedDestinationsBeforeClientExecution() throws Exception {
        var client = mock(CloseableHttpClient.class);
        try (var gateway = new BrowserHttpGateway(client)) {
            assertThatThrownBy(() -> gateway.read(scope, "https://example.com/other", context, deadline())).isInstanceOf(IOException.class);
            var privateScope = new BrowserCaptureScope("https://127.0.0.1/app", List.of("/app"));
            assertThatThrownBy(() -> gateway.read(privateScope, privateScope.url(), context, deadline()))
                    .isInstanceOf(dev.dougle.library.application.LibraryException.class);
            verifyNoInteractions(client);
        }
    }

    private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(30); }
}
