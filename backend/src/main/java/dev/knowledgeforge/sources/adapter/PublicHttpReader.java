package dev.knowledgeforge.sources.adapter;

import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.sources.application.RemoteReader;
import dev.knowledgeforge.sources.domain.HttpValidators;
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
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = InetAddress.getAllByName(host);
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
            String host = uri.getHost();
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
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
        URI uri = validate(requested.toString());
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpGet request = new HttpGet(uri);
            request.setHeader("User-Agent", "KnowledgeForge/0.2");
            request.setHeader("Accept", "*/*");
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
                    var location = response.getFirstHeader("Location");
                    if (location == null) throw new LibraryException(502, "리다이렉트 위치가 없습니다.");
                    return new Response(current.resolve(location.getValue()), "REDIRECT", new byte[0]);
                }
                if (response.getCode() < 200 || response.getCode() >= 300) {
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
