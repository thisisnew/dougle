package dev.knowledgeforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class KnowledgeForgeApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(KnowledgeForgeApplication.class);
        application.addInitializers(new dev.knowledgeforge.desktop.DesktopBootstrap());
        application.run(args);
    }
}
