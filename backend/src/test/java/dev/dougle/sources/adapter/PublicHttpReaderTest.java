package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.URI;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicHttpReaderTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {301, 302, 307, 308, 401, 403, 404, 429, 500})
    @SuppressWarnings("unchecked")
    void authenticatedGithubErrorsNeverFollowRedirectsOrRevealCredentials(int code) throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        String token = "github_pat_synthetic_12345678901234567890";
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer " + token);
            var response = new BasicClassicHttpResponse(code);
            response.addHeader("Location", "https://evil.test/collect");
            if (code == 403) response.addHeader("X-GitHub-SSO", "required; url=https://github.com/orgs/synthetic/sso");
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.readGithub(URI.create("https://api.github.com/repos/company/synthetic/commits?per_page=1"), token))
                    .isInstanceOf(LibraryException.class).hasMessageNotContaining(token).hasMessageNotContaining("evil.test");
        }
        verify(client, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https://evil.test/repos/org/repo/commits", "https://api.github.com/repos/org/repo/issues", "http://api.github.com/repos/org/repo/commits", "https://secret@api.github.com/repos/org/repo/commits", "https://api.github.com:444/repos/org/repo/commits"})
    void refusesCredentialExfiltrationBeforeRequest(String uri) throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.readGithub(URI.create(uri), "github_pat_synthetic_12345678901234567890"))
                    .isInstanceOf(LibraryException.class);
            verifyNoInteractions(client);
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void redirectNeverForwardsValidatorsToAnotherOrigin() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        URI uri = URI.create("https://example.org/document");
        var validators = new dev.dougle.sources.domain.HttpValidators(uri.toString(), "\"synthetic\"", null);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            boolean first = request.getUri().equals(uri);
            var response = new BasicClassicHttpResponse(first ? 302 : 304);
            if (first) {
                assertThat(request.getFirstHeader("If-None-Match").getValue()).isEqualTo(validators.etag());
                response.addHeader("Location", "https://example.net/moved");
            } else {
                assertThat(request.getFirstHeader("If-None-Match")).isNull();
                assertThat(request.getFirstHeader("If-Modified-Since")).isNull();
            }
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.read(uri, validators)).isInstanceOf(LibraryException.class).hasMessageContaining("304");
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void validatorsAreSentOnlyToTheirExactUriAnd304RetainsThem() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        URI original = URI.create("https://example.org/start");
        URI finalUri = URI.create("https://example.org/document");
        var validators = new dev.dougle.sources.domain.HttpValidators(finalUri.toString(), "\"synthetic\"", "Wed, 21 Oct 2026 07:28:00 GMT");
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            boolean redirected = request.getUri().equals(finalUri);
            if (redirected) {
                assertThat(request.getFirstHeader("If-None-Match").getValue()).isEqualTo(validators.etag());
                assertThat(request.getFirstHeader("If-Modified-Since").getValue()).isEqualTo(validators.lastModified());
            } else {
                assertThat(request.getFirstHeader("If-None-Match")).isNull();
                assertThat(request.getFirstHeader("If-Modified-Since")).isNull();
            }
            var response = new BasicClassicHttpResponse(redirected ? 304 : 302);
            if (!redirected) response.addHeader("Location", finalUri.toString());
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            var response = reader.read(original, validators);
            assertThat(response.notModified()).isTrue();
            assertThat(response.validators()).isEqualTo(validators);
            assertThat(response.content()).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnsolicited304AndDropsMalformedValidatorHeaders() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        URI uri = URI.create("https://example.org/document");
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0);
            assertThat(request.getFirstHeader("If-None-Match")).isNull();
            assertThat(request.getFirstHeader("If-Modified-Since")).isNull();
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(new BasicClassicHttpResponse(304));
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.read(uri)).isInstanceOf(LibraryException.class).hasMessageContaining("304");
            var invalid = new dev.dougle.sources.domain.HttpValidators(uri.toString(), "bad\r\nvalue", "x".repeat(1025));
            assertThatThrownBy(() -> reader.read(uri, invalid)).isInstanceOf(LibraryException.class).hasMessageContaining("304");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturesSuccessfulValidatorsWithoutTreatingThemAsContent() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        URI uri = URI.create("https://example.org/document");
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            var response = new BasicClassicHttpResponse(200);
            response.addHeader("ETag", "W/\"synthetic\"");
            response.addHeader("Last-Modified", "x".repeat(1025));
            response.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity("synthetic content"));
            HttpClientResponseHandler<Object> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            var response = reader.read(uri);
            assertThat(response.notModified()).isFalse();
            assertThat(response.validators().etag()).isEqualTo("W/\"synthetic\"");
            assertThat(response.validators().lastModified()).isNull();
            assertThat(new String(response.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("synthetic content");
        }
    }
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
