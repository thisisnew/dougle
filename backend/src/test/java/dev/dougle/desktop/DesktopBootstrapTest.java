package dev.dougle.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dougle.library.adapter.web.LocalRequestFilter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

class DesktopBootstrapTest {
    private static final String TOKEN = "cd".repeat(32);
    @TempDir Path temporary;

    @Test
    void realServerAnnouncesOnceAuthenticatesAndStopsOnParentEof() throws Exception {
        InputStream originalInput = System.in;
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConfigurableApplicationContext context = null;
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream parent = new PipedOutputStream(input);
             PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            parent.write((TOKEN + "\n").getBytes(StandardCharsets.US_ASCII));
            parent.flush();
            System.setIn(input);
            System.setOut(captured);
            SpringApplication application = new SpringApplication(TestServer.class);
            application.addInitializers(new DesktopBootstrap());
            context = application.run("--forge.desktop=true", "--FORGE_DATA_DIR=" + temporary.resolve("data"),
                    "--server.port=0", "--spring.main.banner-mode=off");
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            try (HttpClient client = HttpClient.newHttpClient()) {
                URI uri = URI.create("http://127.0.0.1:" + port + "/api/desktop/ready");
                HttpResponse<String> denied = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).isEqualTo(403);
                HttpResponse<String> ready = client.send(HttpRequest.newBuilder(uri).header("X-Forge-Session", TOKEN)
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(ready.statusCode()).isEqualTo(200);
                assertThat(ready.body()).isEqualTo("{\"ready\":true}");
                assertThat(ready.headers().firstValue("Content-Security-Policy")).isPresent();
            }
            assertThat(output.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("FORGE_DESKTOP_READY:")).toList())
                    .containsExactly("FORGE_DESKTOP_READY:" + port);
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(TOKEN);
            StoreProbe store = context.getBean(StoreProbe.class);
            parent.close();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!store.closed && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(store.closed).isTrue();
            assertThat(context.isActive()).isFalse();
            context.close();
            try (DesktopDataLock released = DesktopDataLock.acquire(temporary.resolve("data").toString())) {
                assertThat(released).isNotNull();
            }
        } finally {
            if (context != null) context.close();
            System.setIn(originalInput);
            System.setOut(originalOutput);
        }
    }

    @Test
    void desktopCannotBypassBootstrapButDevelopmentIsDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of("forge.desktop", "true")));
            context.register(DesktopConfiguration.class);
            assertThatThrownBy(context::refresh).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stdin bootstrap entry point");
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DesktopConfiguration.class)) {
            assertThat(context.getBean(DesktopSession.class).enabled()).isFalse();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({DesktopConfiguration.class, DesktopReadyController.class, LocalRequestFilter.class})
    static class TestServer {
        @Bean(name = "dataSource", destroyMethod = "close")
        StoreProbe storeProbe(Environment environment) {
            return new StoreProbe(environment.getRequiredProperty("forge.data-dir"));
        }
    }

    static class StoreProbe implements AutoCloseable {
        private final String directory;
        private volatile boolean closed;

        StoreProbe(String directory) {
            this.directory = directory;
            assertThatThrownBy(() -> DesktopDataLock.acquire(directory)).isInstanceOf(IllegalStateException.class);
        }

        @Override
        public void close() {
            assertThatThrownBy(() -> DesktopDataLock.acquire(directory)).isInstanceOf(IllegalStateException.class);
            closed = true;
        }
    }
}
