package dev.dougle.desktop;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class DesktopConfiguration {
    @Bean
    static BeanFactoryPostProcessor desktopBootstrapGuard(Environment environment) {
        return factory -> {
            if (!factory.containsSingleton("desktopSession")) {
                if (!"false".equalsIgnoreCase(environment.getProperty("forge.desktop", "false"))) {
                    throw new IllegalStateException("Desktop mode requires the stdin bootstrap entry point");
                }
                factory.registerSingleton("desktopSession", DesktopSession.disabled());
            }
        };
    }
}
