package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebBoundaryMatrixTest {
    record Network(int first, int second, int third, boolean allowed) {}

    static Stream<Arguments> addresses() {
        return Stream.of(
                new Network(0, 0, 0, false), new Network(10, 0, 0, false),
                new Network(127, 0, 0, false), new Network(169, 254, 0, false),
                new Network(172, 16, 0, false), new Network(172, 31, 255, false),
                new Network(192, 168, 0, false), new Network(192, 0, 0, false),
                new Network(100, 64, 0, false), new Network(100, 127, 255, false),
                new Network(198, 18, 0, false), new Network(198, 19, 255, false),
                new Network(198, 51, 100, false), new Network(203, 0, 113, false),
                new Network(224, 0, 0, false), new Network(239, 255, 255, false),
                new Network(240, 0, 0, false), new Network(255, 255, 255, false),
                new Network(100, 63, 255, true), new Network(100, 128, 0, true),
                new Network(172, 15, 255, true), new Network(172, 32, 0, true),
                new Network(192, 167, 255, true), new Network(192, 169, 0, true),
                new Network(198, 17, 255, true), new Network(198, 20, 0, true),
                new Network(198, 51, 99, true), new Network(198, 51, 101, true),
                new Network(203, 0, 112, true), new Network(203, 0, 114, true),
                new Network(169, 253, 255, true), new Network(169, 255, 0, true))
                .flatMap(network -> IntStream.range(0, 16).mapToObj(sample -> Arguments.of(
                        network.first() + "." + network.second() + "." + network.third() + "." + sample * 17,
                        network.allowed())));
    }

    @ParameterizedTest(name = "WEB-IP-{index}: address={0} public={1}")
    @MethodSource("addresses")
    void literalAddressPolicyMatchesIndependentNetworkExpectations(String address, boolean allowed) throws Exception {
        assertThat(PublicHttpReader.publicAddress(InetAddress.getByName(address))).isEqualTo(allowed);
        String location = "https://" + address + "/document";
        if (allowed) assertThat(WebSourcePolicy.validate(location)).isEqualTo(URI.create(location));
        else assertThatThrownBy(() -> WebSourcePolicy.validate(location)).isInstanceOf(LibraryException.class);
    }

    static List<String> paths() {
        return List.of("", "/", "/a/b", "/a%20b", "/%ED%95%9C%EA%B8%80", "/a/../b",
                "/%2e%2e/b", "/a%2Fb", "/a?next=https://example.test", "/?q=%23",
                "/a;b=1", "/?q=%F0%9F%8C%B1&empty=");
    }

    static Stream<Arguments> hosts() {
        return Stream.of("localhost", "LOCALHOST", "localhost.", "LOCALHOST.",
                "api.localhost", "api.LOCALHOST", "api.localhost.", "api.LOCALHOST.",
                "printer.local", "printer.LOCAL", "printer.local.", "printer.LOCAL.",
                "github.com", "GITHUB.COM.", "api.github.com", "raw.githubusercontent.com",
                "github.com.example.test", "localhost.example.test", "example.test", "docs.example.test")
                .flatMap(host -> paths().stream().map(path -> Arguments.of(host, path, host.endsWith("example.test"))));
    }

    @ParameterizedTest(name = "WEB-HOST-{index}: host={0} path={1} allowed={2}")
    @MethodSource("hosts")
    void hostRestrictionsCannotBeBypassedByCaseRootDotsOrPaths(String host, String path, boolean allowed) {
        String location = "https://" + host + path;
        if (allowed) assertThat(WebSourcePolicy.validate(location).toString()).isEqualTo(location);
        else assertThatThrownBy(() -> WebSourcePolicy.validate(location)).isInstanceOf(LibraryException.class);
    }

    static Stream<Arguments> redirects() {
        return IntStream.of(301, 302, 307, 308).boxed().flatMap(status ->
                Stream.of("https://github.com", "https://127.0.0.1")
                        .flatMap(host -> paths().stream().map(path -> Arguments.of(status, host + path))));
    }

    @ParameterizedTest(name = "WEB-REDIRECT-{index}: status={0} destination={1}")
    @MethodSource("redirects")
    @SuppressWarnings("unchecked")
    void forbiddenRedirectsStopAfterOneReadOnlyRequest(int status, String destination) throws Exception {
        var client = mock(CloseableHttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(call -> {
            ClassicHttpRequest request = call.getArgument(0);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(request.getFirstHeader("Authorization")).isNull();
            var response = new BasicClassicHttpResponse(status);
            response.addHeader("Location", destination);
            HttpClientResponseHandler<Object> handler = call.getArgument(1);
            return handler.handleResponse(response);
        });
        try (var reader = new PublicHttpReader(client)) {
            assertThatThrownBy(() -> reader.read(URI.create("https://example.test/start")))
                    .isInstanceOf(LibraryException.class);
        }
        verify(client, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }
}
