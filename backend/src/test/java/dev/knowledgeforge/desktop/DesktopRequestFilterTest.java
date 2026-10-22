package dev.knowledgeforge.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.knowledgeforge.library.adapter.web.LocalRequestFilter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DesktopRequestFilterTest {
    private static final String TOKEN = "ab".repeat(32);

    @Test
    void allApiMethodsIncludingDownloadsAndEventsRequireHeader() throws Exception {
        for (String path : new String[]{"/api", "/api/desktop/ready", "/api/entries/id/download", "/api/answer-jobs/id/events"}) {
            for (String method : new String[]{"GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"}) {
                assertThat(request(true, method, path, null, null, "localhost", true).getStatus()).isEqualTo(403);
                assertThat(request(true, method, path, TOKEN, null, "localhost", true).getStatus()).isEqualTo(204);
            }
        }
    }

    @Test
    void unknownAndMatrixPathsCannotBypassAuthentication() throws Exception {
        for (String path : new String[]{"/api;extra/session", "//api/session", "/assets/../api/session", "/other-data"}) {
            assertThat(request(true, "GET", path, null, null, "localhost", true).getStatus()).isEqualTo(403);
        }
        assertThat(request(true, "GET", "/assets/index-synthetic.js", null, null, "localhost", false).getStatus()).isEqualTo(204);
    }
    @Test
    void retainsHostOriginAndMutationChecksAndExcludesDevOrigin() throws Exception {
        assertThat(request(true, "GET", "/api/test", TOKEN, "http://localhost:5173", "localhost", true).getStatus()).isEqualTo(403);
        assertThat(request(true, "GET", "/api/test", TOKEN, "http://localhost:8787", "localhost", true).getStatus()).isEqualTo(204);
        assertThat(request(true, "GET", "/api/test", TOKEN, null, "evil.test", true).getStatus()).isEqualTo(403);
        assertThat(request(true, "POST", "/api/test", TOKEN, null, "localhost", false).getStatus()).isEqualTo(403);
        assertThat(request(false, "GET", "/api/test", null, "http://localhost:5173", "localhost", false).getStatus()).isEqualTo(204);
    }

    @Test
    void staticUiNeedsNoTokenAndReceivesRestrictiveCsp() throws Exception {
        MockHttpServletResponse response = request(true, "GET", "/index.html", null, null, "localhost", false);
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("Content-Security-Policy")).contains("script-src 'self'", "connect-src 'self'", "object-src 'none'");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    private MockHttpServletResponse request(boolean enabled, String method, String path, String token,
                                            String origin, String host, boolean client) throws Exception {
        DesktopSession session = enabled ? DesktopSession.read(new ByteArrayInputStream((TOKEN + "\n")
                .getBytes(StandardCharsets.US_ASCII))) : DesktopSession.disabled();
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName(host);
        request.setServerPort(8787);
        if (token != null) request.addHeader("X-Forge-Session", token);
        if (origin != null) request.addHeader("Origin", origin);
        if (client) request.addHeader("X-Forge-Client", "1");
        request.setQueryString("token=" + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LocalRequestFilter(session).doFilter(request, response, (incoming, outgoing) -> response.setStatus(204));
        return response;
    }
}
