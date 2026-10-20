package dev.knowledgeforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class KnowledgeForgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeForgeApplication.class, args);
    }
}
