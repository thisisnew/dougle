package dev.dougle;

import dev.dougle.plugins.adapter.*;
import dev.dougle.library.application.LibraryService;
import dev.dougle.library.application.LibraryException;
import dev.dougle.search.application.SearchService;
import dev.dougle.sources.adapter.CollectionLimits;
import dev.dougle.sources.domain.CollectedSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PluginIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("forge-plugin-fixtures-"); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired PluginConnections connections;
    @Autowired LibraryService library;
    @Autowired SearchService search;
    @MockitoBean NotionClient notion;
    @MockitoBean MysqlClient mysql;
    String token = "ntn_synthetic_integration_test_12345";
    String config = "{\"host\":\"localhost\",\"port\":3306,\"database\":\"fixture\",\"username\":\"reader\",\"password\":\"synthetic-secret\",\"sslMode\":\"DISABLED\"}";
    @BeforeEach void resetConnections() { connections.disconnect("notion"); connections.disconnect("mysql"); }

    @Test void browserEndpointsRequireSameOriginAndRejectUnknownProviders() throws Exception {
        mvc.perform(post("/api/plugins/browser/notion").header("Origin", "https://example.org").header("X-Forge-Client", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/plugins/browser/unknown").header("X-Forge-Client", "1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/browser/notion/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins")).andExpect(jsonPath("$.atlassianConnected").value(false));
    }

    @Test void notionTestSaveReadAndDisconnectNeverExposeToken() throws Exception {
        String body = "{\"token\":\"" + token + "\"}";
        mvc.perform(post("/api/plugins/notion/test").header("X-Forge-Client", "1").contentType("application/json").content(body)).andExpect(status().isNoContent());
        assertThat(connections.status().notionConnected()).isFalse();
        mvc.perform(post("/api/plugins/notion").header("X-Forge-Client", "1").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notionConnected").value(true)).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
        mvc.perform(get("/api/plugins")).andExpect(status().isOk()).andExpect(jsonPath("$.notionConnected").value(true));
        mvc.perform(delete("/api/plugins/notion").header("X-Forge-Client", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.notionConnected").value(false));
    }

    @Test void failedValidationDoesNotSaveAndCrossOriginMutationIsRejected() throws Exception {
        doThrow(new LibraryException(400, "토큰이 유효하지 않습니다.")).when(notion).test(anyString());
        mvc.perform(post("/api/plugins/notion").header("X-Forge-Client", "1").contentType("application/json").content("{\"token\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        assertThat(connections.status().notionConnected()).isFalse();
        mvc.perform(post("/api/plugins/mysql").header("Origin", "https://example.org").header("X-Forge-Client", "1").contentType("application/json").content(config)).andExpect(status().isForbidden());
        verifyNoInteractions(mysql);
    }

    @Test void mysqlSchemaCanBeRegisteredSyncedAndSearchedWithoutPasswordInSource() throws Exception {
        mvc.perform(post("/api/plugins/mysql").header("X-Forge-Client", "1").contentType("application/json").content(config))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mysql.password").value(""));
        when(mysql.schema(any())).thenReturn(CollectionLimits.snapshot(List.of(new CollectedSnapshot.File("mysql-schema.txt", "Synthetic inventory table fixture_column".getBytes()))));
        String workspace = library.createWorkspace("가상 DB 연결").id();
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1").contentType("application/json")
                        .content("{\"name\":\"fixture 구조\",\"kind\":\"MYSQL\",\"location\":\"localhost:3306/fixture\",\"sync\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic-secret"))));
        assertThat(search.search(workspace, "fixture_column", List.of())).isNotEmpty();
    }

    @Test void githubAuthenticationEndpointsAreRemoved() throws Exception {
        mvc.perform(get("/api/plugins/github").header("X-Forge-Client", "1")).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/plugins/github").header("X-Forge-Client", "1").contentType("application/json").content("{}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/plugins/github/open").header("X-Forge-Client", "1")).andExpect(status().is4xxClientError());
    }

    @Test void connectedNotionDoesNotBypassRemovedUrlRegistration() throws Exception {
        connections.notion(token, true);
        String url = "https://app.notion.com/p/359fdb093f9380648576c536ea909b2d";
        when(notion.collect(url, token)).thenReturn(CollectionLimits.snapshot(List.of(new CollectedSnapshot.File("notion-page.txt", "Synthetic waterfall methodology".getBytes()))));
        String workspace = library.createWorkspace("가상 Notion 연결").id();
        mvc.perform(post("/api/workspaces/" + workspace + "/sources").header("X-Forge-Client", "1").contentType("application/json")
                        .content("{\"name\":\"가상 페이지\",\"kind\":\"URL\",\"location\":\"" + url + "\",\"sync\":true}"))
                .andExpect(status().isBadRequest());
        assertThat(search.search(workspace, "waterfall", List.of())).isEmpty();
        verify(notion, never()).collect(url, token);
    }
}
