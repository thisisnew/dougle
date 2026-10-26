package dev.knowledgeforge.investigation.application;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class BrowserCaptureScopeTest {
    @Test void approvedPathNeverAuthorizesMutationMethodsOrMethodOverrides() {
        var approved = new BrowserCaptureScope("https://example.com/resource", List.of("/resource"));
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "CONNECT", "TRACE", "OPTIONS", "HEAD", "get", "GET ")) {
            assertThat(approved.allows(method, approved.url(), false)).isFalse();
            assertThat(approved.allows(method, approved.url(), true)).isFalse();
        }
        assertThat(approved.allows("GET", approved.url() + "?_method=DELETE", false)).isFalse();
    }
    private final BrowserCaptureScope scope = new BrowserCaptureScope("https://example.com/app", List.of("/app", "/api/status", "/app.js"));

    @Test void exactOriginMethodPathAndNavigationAreBound() {
        assertThat(scope.allows("GET", "https://example.com/app", true)).isTrue();
        assertThat(scope.allows("GET", "https://example.com/api/status", false)).isTrue();
        assertThat(scope.allows("GET", "https://example.com/api/status", true)).isFalse();
        assertThat(scope.allows("POST", "https://example.com/api/status", false)).isFalse();
        assertThat(scope.allows("GET", "https://example.com/api/status/extra", false)).isFalse();
        assertThat(scope.allows("GET", "https://other.example/app", false)).isFalse();
        assertThat(scope.observationPath("https://example.com/api/status?token=PRIVATE")).isEqualTo("/api/status");
        assertThat(scope.observationPath("https://other.example/private-token")).isEqualTo("[outside-approved-scope]");
        assertThat(scope.allowedGetPaths()).containsExactly("/api/status", "/app", "/app.js");
    }

    @Test void candidatePathsAreSuggestionsNotScopeExpansion() {
        String request = "https://example.com/api/metrics?token=PRIVATE#PRIVATE";
        assertThat(scope.observationPath(request)).isEqualTo("/api/metrics");
        assertThat(scope.candidatePath("GET", request)).isEqualTo("/api/metrics");
        assertThat(scope.allows("GET", request, false)).isFalse();
        assertThat(scope.allows("GET", "https://example.com/api/metrics", false)).isFalse();
        assertThat(scope.candidatePath("POST", request)).isNull();
        assertThat(scope.candidatePath("GET", "https://example.com/api/status")).isNull();
        assertThat(scope.candidatePath("GET", "https://example.com/" + "x".repeat(160))).isNull();
    }

    @ParameterizedTest @MethodSource("unsafeCandidates")
    void candidateDisplayCannotLeakCredentialsOrCrossOriginAndAmbiguousPaths(String url) {
        assertThat(scope.candidatePath("GET", url)).isNull();
        assertThat(scope.observationPath(url)).startsWith("[");
    }

    static Stream<String> unsafeCandidates() {
        return Stream.of("https://user:PRIVATE@example.com/api/new", "https://other.example/api/PRIVATE",
                "https://example.com:444/api/new", "http://example.com/api/new", "file:///api/new",
                "https://example.com/api/%50RIVATE", "https://example.com/api/../new",
                "https://example.com//api/new", "https://example.com/api/PRIVATE\n");
    }

    @ParameterizedTest @MethodSource("invalidUrls")
    void rejectsUnsupportedOrAmbiguousScope(String url) {
        assertThatThrownBy(() -> new BrowserCaptureScope(url, List.of("/app"))).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> invalidUrls() {
        return Stream.of("http://example.com/app", "file:///app", "https://user:pass@example.com/app",
                "https://example.com/app?token=PRIVATE", "https://example.com/app#token",
                "https://example.com:443/app", "https://example.com/app/../app", "https://example.com/%61pp",
                "https://example.com//app", "https://example.com./app", "https://example.com/app\\secret",
                "https://example.com/app\n", "https://example.com/elsewhere");
    }

    @Test void pathListsAreBoundedAndImmutable() {
        assertThatThrownBy(() -> new BrowserCaptureScope(scope.url(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrowserCaptureScope(scope.url(), java.util.Collections.nCopies(33, "/app"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.allowedGetPaths().add("/new")).isInstanceOf(UnsupportedOperationException.class);
    }
}
