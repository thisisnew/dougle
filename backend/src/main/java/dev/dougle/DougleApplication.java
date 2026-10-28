package dev.dougle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class DougleApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DougleApplication.class);
        application.addInitializers(new dev.dougle.desktop.DesktopBootstrap());
        application.run(args);
    }
}
