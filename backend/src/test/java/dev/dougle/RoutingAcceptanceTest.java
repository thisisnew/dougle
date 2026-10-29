package dev.dougle;

import dev.dougle.library.application.LibraryService;
import dev.dougle.sources.application.RemoteReader;
import dev.dougle.sources.adapter.GitRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "forge.acceptance.routing", matches = "true")
class RoutingAcceptanceTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-routing-acceptance-"); }
        catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("forge.data-dir", DATA::toString);
    }

    @Autowired LibraryService library;
    @Autowired MockMvc mvc;
    @MockitoBean RemoteReader remote;
    @MockitoBean GitRunner git;
    final JsonMapper json = JsonMapper.builder().build();

    @TestFactory
    List<DynamicTest> registrationMatrix() throws Exception {
        var fixtures = json.readTree(Files.readString(Path.of("../docs/fixtures/routing-acceptance.json")));
        var tests = new ArrayList<DynamicTest>();
        for (var fixture : fixtures) {
            String caseId = fixture.path("caseIds").path("api").asText();
            tests.add(DynamicTest.dynamicTest(caseId, () -> {
                for (String selected : List.of("URL", "GITHUB")) {
                    boolean supported = "URL".equals(selected)
                            && "https://example.org/guide".equals(fixture.path("input").asText());
                    String workspace = library.createWorkspace(caseId + selected).id();
                    var body = json.createObjectNode().put("name", caseId).put("kind", selected)
                            .put("location", fixture.path("input").asText()).put("domain", "").put("sync", !supported);
                    var response = mvc.perform(post("/api/workspaces/" + workspace + "/sources")
                            .header("X-Forge-Client", "1")
                            .contentType("application/json").content(body.toString())).andReturn().getResponse();
                    assertThat(response.getStatus()).isEqualTo(supported ? 201 : 400);
                    var listed = mvc.perform(get("/api/workspaces/" + workspace + "/sources")).andReturn().getResponse();
                    assertThat(listed.getStatus()).isEqualTo(200);
                    var sources = json.readTree(listed.getContentAsString());
                    assertThat(sources.size()).isEqualTo(supported ? 1 : 0);
                    if (supported) {
                        var created = json.readTree(response.getContentAsString());
                        assertThat(created.path("kind").asText()).isEqualTo("URL");
                        assertThat(created.path("location").asText()).isEqualTo("https://example.org/guide");
                        assertThat(sources.path(0).path("id").asText()).isEqualTo(created.path("id").asText());
                    }
                    verifyNoInteractions(remote, git);
                }
            }));
        }
        return tests;
    }
}
