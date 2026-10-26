package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.BrowserCaptureScope;
import dev.knowledgeforge.investigation.application.BrowserCaptureTransport;
import dev.knowledgeforge.investigation.domain.ToolExecution;
import dev.knowledgeforge.sources.adapter.PublicHttpReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

class BrowserHttpGateway implements AutoCloseable {
    record Response(int status, String contentType, byte[] body) {}
    private final CloseableHttpClient client;
    private int bytes;

    BrowserHttpGateway() {
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                var addresses = InetAddress.getAllByName(host);
                for (var address : addresses) if (!PublicHttpReader.publicAddress(address)) throw new UnknownHostException("Non-public target");
                return addresses;
            }
            public String resolveCanonicalHostname(String host) { return host; }
        }).build();
        client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3)).setResponseTimeout(Timeout.ofSeconds(5)).build()).build();
    }

    BrowserHttpGateway(CloseableHttpClient client) { this.client = client; }

    Response read(BrowserCaptureScope scope, String url, ToolExecution.Context context, long deadline) throws Exception {
        context.checkpoint();
        if (!scope.allows("GET", url, false)) throw new IOException("Scope denied");
        URI uri = PublicHttpReader.validate(url);
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept-Encoding", "identity");
        request.setHeader("User-Agent", "Dougle-ReadOnly-Capture/1");
        try (var timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory())) {
            timer.scheduleAtFixedRate(() -> {
                if (System.nanoTime() >= deadline || context.cancelled().getAsBoolean()) request.cancel();
            }, 0, 50, TimeUnit.MILLISECONDS);
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) return new Response(status, "text/plain", new byte[0]);
                var entity = response.getEntity();
                if (entity == null) return new Response(status, "text/plain", new byte[0]);
                if (response.containsHeader("Content-Encoding")) throw new IOException("Compressed response denied");
                String type = entity.getContentType();
                if (type == null || type.length() > 128 || !type.matches("[a-zA-Z0-9/;= ._-]+")) throw new IOException("Unsupported content type");
                if (!(type.startsWith("text/") || type.startsWith("application/json") || type.startsWith("application/javascript"))) {
                    throw new IOException("Only text, JavaScript and JSON resources supported");
                }
                if (entity.getContentLength() > BrowserCaptureTransport.MAX_RESPONSE_BYTES) throw new IOException("Resource limit");
                try (var input = entity.getContent()) {
                    byte[] body = input.readNBytes(BrowserCaptureTransport.MAX_RESPONSE_BYTES + 1);
                    bytes += body.length;
                    if (body.length > BrowserCaptureTransport.MAX_RESPONSE_BYTES || bytes > BrowserCaptureTransport.MAX_TOTAL_BYTES) {
                        throw new IOException("Capture byte limit");
                    }
                    context.checkpoint();
                    return new Response(status, type, body);
                }
            });
        }
    }

    public void close() throws IOException { client.close(); }
}
