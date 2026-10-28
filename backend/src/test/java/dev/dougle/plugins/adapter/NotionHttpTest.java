package dev.dougle.plugins.adapter;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotionHttpTest {
    private final String token = "ntn_synthetic_never_real_12345";
    @Test void sendsCredentialOnlyToFixedApiAndDoesNotReturnRemoteErrorBody() throws Exception {
        var http = mock(CloseableHttpClient.class);
        var status = new AtomicReference<>(200);
        when(http.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            assertThat(request.getUri().getHost()).isEqualTo("api.notion.com");
            assertThat(request.getUri().getScheme()).isEqualTo("https");
            assertThat(request.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer " + token);
            assertThat(request.getFirstHeader("Notion-Version").getValue()).isEqualTo("2025-09-03");
            var response = new BasicClassicHttpResponse(status.get());
            response.setEntity(new StringEntity(status.get() == 200 ? "{\"id\":\"synthetic-bot\"}" : token));
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        var notion = new NotionClient(http);
        try {
            notion.test(token);
            status.set(401);
            assertThatThrownBy(() -> notion.test(token)).hasMessageContaining("토큰").hasMessageNotContaining(token);
            status.set(404);
            assertThatThrownBy(() -> notion.test(token)).hasMessageContaining("접근 권한").hasMessageNotContaining(token);
            status.set(429);
            assertThatThrownBy(() -> notion.test(token)).hasMessageContaining("요청 한도").hasMessageNotContaining(token);
            status.set(302);
            assertThatThrownBy(() -> notion.test(token)).hasMessageContaining("응답").hasMessageNotContaining(token);
            verify(http, times(5)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        } finally { notion.close(); }
    }
}
