package dev.dougle.sources.adapter;

import dev.dougle.library.application.LibraryException;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.domain.HttpValidators;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
public class PublicHttpReader implements RemoteReader, AutoCloseable {
    private final CloseableHttpClient client;
    private final java.util.concurrent.ScheduledExecutorService deadlines =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    public PublicHttpReader() {
        this(org.apache.hc.client5.http.SystemDefaultDnsResolver.INSTANCE);
    }

    PublicHttpReader(DnsResolver resolver) {
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = resolver.resolve(host);
                if (addresses == null || addresses.length == 0) throw new UnknownHostException("Empty DNS response rejected");
                for (InetAddress address : addresses) {
                    if (!publicAddress(address)) throw new UnknownHostException("Non-public destination rejected");
                }
                return addresses;
            }

            public String resolveCanonicalHostname(String host) { return host; }
        }).build();
        client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableCookieManagement()
                .disableAutomaticRetries().setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5)).setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(15)).build()).build();
    }

    PublicHttpReader(CloseableHttpClient client) {
        this.client = client;
    }

    public static URI validate(String location) {
        try {
            URI uri = URI.create(location);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT).replaceAll("\\.+$", "");
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
                throw new IllegalArgumentException();
            }
            if (host.contains(":") || host.matches("[0-9.]+")) {
                try {
                    if (!publicAddress(InetAddress.getByName(host))) throw new IllegalArgumentException();
                } catch (UnknownHostException exception) {
                    throw new IllegalArgumentException(exception);
                }
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new LibraryException(400, "사용자정보·fragment가 없는 공개 HTTPS URL(443)을 입력하세요.");
        }
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(first == 203 && second == 0 && third == 113);
        }
        return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0)
                && !(first == 0x20 && second == 0x01 && bytes[2] == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8);
    }

    public Response read(URI requested) throws Exception {
        return read(requested, null);
    }

    public Response read(URI requested, HttpValidators validators) throws Exception {
        return read(requested, validators, null, false);
    }

    public Response readGithub(URI requested, String token) throws Exception {
        if (!"api.github.com".equals(requested.getHost()) || !requested.getPath().matches("/repos/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+/commits")) {
            throw new LibraryException(400, "GitHub 읽기 요청 주소가 올바르지 않습니다.");
        }
        if (token != null && !token.matches("[A-Za-z0-9_]{20,255}")) throw new LibraryException(400, "GitHub 토큰 형식을 확인하세요.");
        return read(requested, null, token, true);
    }

    private Response read(URI requested, HttpValidators validators, String token, boolean githubRequest) throws Exception {
        URI uri = validate(requested.toString());
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!githubRequest) WebSourcePolicy.validate(uri.toString());
            HttpGet request = new HttpGet(uri);
            request.setHeader("User-Agent", "Dougle/0.2");
            request.setHeader("Accept", "*/*");
            if (token != null) request.setHeader("Authorization", "Bearer " + token);
            String etag = validators != null && uri.toString().equals(validators.uri()) ? safeHeader(validators.etag()) : null;
            String modified = validators != null && uri.toString().equals(validators.uri()) ? safeHeader(validators.lastModified()) : null;
            if (etag != null) request.setHeader("If-None-Match", etag);
            if (modified != null) request.setHeader("If-Modified-Since", modified);
            URI current = uri;
            var deadline = deadlines.schedule(request::cancel, 25, java.util.concurrent.TimeUnit.SECONDS);
            Response result;
            try {
                result = client.execute(request, response -> {
                String responseEtag = header(response, "ETag");
                String responseModified = header(response, "Last-Modified");
                if (response.getCode() == 304) {
                    if (etag == null && modified == null) throw new LibraryException(502, "조건부 요청 없는 304 응답입니다.");
                    return new Response(current, null, new byte[0], true, new HttpValidators(current.toString(),
                            responseEtag == null ? etag : responseEtag, responseModified == null ? modified : responseModified));
                }
                if (response.getCode() >= 300 && response.getCode() < 400) {
                    if (token != null) throw new LibraryException(502, "인증된 GitHub 요청의 주소 변경을 거부했습니다.");
                    var location = response.getFirstHeader("Location");
                    if (location == null) throw new LibraryException(502, "리다이렉트 위치가 없습니다.");
                    return new Response(current.resolve(location.getValue()), "REDIRECT", new byte[0]);
                }
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    if ("api.github.com".equals(current.getHost())) {
                        throw new LibraryException(response.getCode(), switch (response.getCode()) {
                            case 401 -> "GitHub 인증이 만료되었거나 유효하지 않습니다. 설정에서 다시 연결하세요.";
                            case 403 -> response.getFirstHeader("X-GitHub-SSO") != null
                                    ? "회사 SSO 승인이 필요합니다. GitHub에서 회사 계정으로 로그인하고 조직 접근을 승인하세요."
                                    : "GitHub 조회가 거부되었습니다. 조직의 앱·토큰 승인, 저장소 읽기 권한과 요청 한도를 확인하세요.";
                            case 404 -> "저장소가 없거나 접근 권한이 없습니다. 주소와 GitHub 연결·회사 조직 승인을 확인하세요.";
                            case 429 -> "GitHub 요청 한도에 도달했습니다. 잠시 후 다시 동기화하세요.";
                            default -> "GitHub 응답 오류입니다. 잠시 후 다시 동기화하세요. (HTTP " + response.getCode() + ")";
                        });
                    }
                    throw new LibraryException(502, "원격 서버 HTTP 오류: " + response.getCode());
                }
                var saved = responseEtag == null && responseModified == null ? null
                        : new HttpValidators(current.toString(), responseEtag, responseModified);
                if (response.getEntity() == null) return new Response(current, "application/octet-stream", new byte[0], false, saved);
                if (response.getEntity().getContentLength() > CollectionLimits.MAX_FILE) {
                    throw new LibraryException(413, "응답이 10 MiB 제한을 초과했습니다.");
                }
                try (var input = response.getEntity().getContent()) {
                    return new Response(current, response.getEntity().getContentType(), CollectionLimits.read(input), false, saved);
                }
                });
            } finally {
                deadline.cancel(false);
            }
            if (!"REDIRECT".equals(result.mediaType())) return result;
            uri = validate(result.uri().toString());
        }
        throw new LibraryException(502, "리다이렉트 한도(3회)를 초과했습니다.");
    }

    private static String header(org.apache.hc.core5.http.HttpResponse response, String name) {
        var header = response.getFirstHeader(name);
        return header == null ? null : safeHeader(header.getValue());
    }

    private static String safeHeader(String value) {
        return value == null || value.isBlank() || value.length() > 1024
                || value.codePoints().anyMatch(Character::isISOControl) ? null : value;
    }

    @PreDestroy
    public void close() throws java.io.IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
