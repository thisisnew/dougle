package dev.knowledgeforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RemovedIntegrationsTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-removed-integrations-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired WebApplicationContext context;

    @Test void retiredEndpointsHaveNoHandlerForAnyMethod() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        for (String path : List.of("/api/slack/status", "/api/slack/connection", "/api/slack/workspaces/synthetic/inbox",
                "/api/slack/workspaces/synthetic/inbox-count", "/api/slack/workspaces/synthetic/replies",
                "/api/plugins/datadog/synthetic", "/api/workspaces/synthetic/datadog-investigations/preview")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isNotFound());
            mvc.perform(delete(path)).andExpect(status().isNotFound());
        }
    }

    @Test void noRetiredProviderOrSchedulerIsRegistered() {
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).matches(".*(slack|datadog).*"));
        for (String name : List.of("dev/knowledgeforge/plugins/slack/adapter/SlackSocketProvider.class",
                "dev/knowledgeforge/plugins/adapter/DatadogLogsClient.class")) {
            assertThat(getClass().getClassLoader().getResource(name)).isNull();
        }
    }
}
