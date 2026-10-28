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
                    String workspace = library.createWorkspace(caseId + selected).id();
                    var body = json.createObjectNode().put("name", caseId).put("kind", selected)
                            .put("location", fixture.path("input").asText()).put("domain", "").put("sync", false);
                    var response = mvc.perform(post("/api/workspaces/" + workspace + "/sources")
                            .header("X-Forge-Client", "1")
                            .contentType("application/json").content(body.toString())).andReturn().getResponse();
                    assertThat(response.getStatus()).isEqualTo(400);
                    var listed = mvc.perform(get("/api/workspaces/" + workspace + "/sources")).andReturn().getResponse();
                    assertThat(json.readTree(listed.getContentAsString()).size()).isZero();
                    verifyNoInteractions(remote, git);
                }
            }));
        }
        return tests;
    }
}
