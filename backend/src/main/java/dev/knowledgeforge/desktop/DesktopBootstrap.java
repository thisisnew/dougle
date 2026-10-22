package dev.knowledgeforge.desktop;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.MapPropertySource;

public final class DesktopBootstrap implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String mode = context.getEnvironment().getProperty("forge.desktop", "false");
        if ("false".equalsIgnoreCase(mode)) return;
        if (!"true".equalsIgnoreCase(mode)) throw new IllegalStateException("Invalid forge.desktop mode");
        String directory = context.getEnvironment().getProperty("FORGE_DATA_DIR");
        java.io.InputStream input = System.in;
        DesktopSession session = DesktopSession.read(input);
        DesktopDataLock lock;
        try {
            lock = DesktopDataLock.acquire(directory);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot secure desktop data directory");
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("desktopConstraints", Map.of(
                "forge.data-dir", directory, "server.address", "127.0.0.1", "server.port", "0",
                "server.servlet.context-path", "", "spring.mvc.servlet.path", "/")));
        context.getBeanFactory().registerSingleton("desktopSession", session);
        context.getBeanFactory().registerSingleton("desktopDataLock", lock);
        ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                .registerDisposableBean("desktopDataLock", lock::close);
        context.getBeanFactory().registerDependentBean("desktopDataLock", "dataSource");
        AtomicBoolean watching = new AtomicBoolean();
        AtomicBoolean announced = new AtomicBoolean();
        context.addApplicationListener(event -> {
            if (event instanceof ContextRefreshedEvent refreshed && refreshed.getApplicationContext() == context
                    && watching.compareAndSet(false, true)) {
                session.watchParent(input, context::close);
            }
            if (event instanceof ApplicationReadyEvent ready && ready.getApplicationContext() == context
                    && context.isActive() && announced.compareAndSet(false, true)) {
                if (!session.markReady()) return;
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                System.out.println("FORGE_DESKTOP_READY:" + port);
                System.out.flush();
            }
        });
    }
}
