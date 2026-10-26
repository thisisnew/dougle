package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.conversation.application.ChatThreadService;
import dev.knowledgeforge.investigation.application.OperationalScopeClarification;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Origin;
import dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool;
import dev.knowledgeforge.library.application.LibraryException;
import dev.knowledgeforge.library.application.LibraryService;
import dev.knowledgeforge.plugins.adapter.DatadogConnections;
import dev.knowledgeforge.plugins.adapter.DatadogLogsClient;
import dev.knowledgeforge.plugins.adapter.MysqlClient;
import dev.knowledgeforge.plugins.adapter.NotionClient;
import dev.knowledgeforge.plugins.adapter.PluginConnections;
import dev.knowledgeforge.search.application.ConversationRetrieval;
import dev.knowledgeforge.search.domain.EvidenceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class ServerOperationalScopeAuthorityIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("dougle-scope-authority-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("forge.data-dir", DATA::toString); }
    @Autowired ServerOperationalScopeAuthority authority;
    @Autowired ChatThreadService threads;
    @Autowired LibraryService library;
    @Autowired PluginConnections mysql;
    @Autowired DatadogConnections datadog;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ConversationRetrieval retrieval;
    @MockitoBean MysqlClient mysqlClient;
    @MockitoBean NotionClient notionClient;
    @MockitoBean DatadogLogsClient logsClient;
    private String workspace;
    private EvidenceBundle parent;
    private OperationalScopeClarification scopes;

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM workspace");
        mysql.disconnect("mysql");
        when(retrieval.retrieve(anyString(), anyList(), any())).thenReturn(
                new ConversationRetrieval.Result(ConversationRetrieval.Status.EMPTY, List.of()));
        workspace = library.createWorkspace("scope fixture").id();
        parent = question(workspace, "first", null, null);
        scopes = new OperationalScopeClarification(authority, Clock.systemUTC());
    }

    @AfterEach void noExternalExecution() {
        verifyNoInteractions(notionClient, logsClient);
        verify(mysqlClient, never()).schema(any());
    }

    private EvidenceBundle question(String owner, String request, String thread, String previous) {
        return threads.create(owner, new ChatThreadService.Create(request, thread, previous, "Synthetic scope question", List.of()));
    }
    private String thread() { return parent.payload().chatContext().threadId(); }
    private PendingOperationalScope open(OperationalTool tool) {
        PendingOperationalScope.Candidate candidate = tool == OperationalTool.MYSQL_READ
                ? new PendingOperationalScope.Mysql(null, null, null, null)
                : new PendingOperationalScope.Datadog(null, null, null, null, null, null, null);
        return scopes.open(workspace, thread(), parent.id(), candidate, Origin.MODEL_PROPOSAL);
    }
    private void connect(OperationalTool tool) {
        if (tool == OperationalTool.MYSQL_READ) mysql.mysql(new MysqlClient.Config(
                "localhost", 3306, "fixture", "reader", "synthetic-password", "DISABLED"), true);
        else datadog.save(workspace, new DatadogLogsClient.Config(DatadogLogsClient.Site.EU1,
                "synthetic_api_key_0000000000000000", "synthetic_app_key_0000000000000000"));
    }
    private void disconnect(OperationalTool tool) {
        if (tool == OperationalTool.MYSQL_READ) mysql.disconnect("mysql");
        else datadog.disconnect(workspace);
    }

    @Test void realAuthorityIsProxiedAndDisconnectedBindingIsServerDerivedAndNonAuthorizing() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(authority)).isTrue();
        for (var tool : OperationalTool.values()) {
            var scope = open(tool);
            assertThat(scope.binding().connectionRevision()).isNull();
            assertThat(scope.binding().connectionGeneration()).isNotBlank();
            assertThat(scopes.get(workspace, scope.id())).isEqualTo(scope);
            assertThat(scope.executionAllowed()).isFalse();
        }
        verifyNoInteractions(mysqlClient);
    }

    @Test void deletedWorkspaceForeignThreadAndStaleLatestParentFailClosed() {
        var scope = open(OperationalTool.MYSQL_READ);
        String other = library.createWorkspace("other scope").id();
        var foreign = question(other, "foreign", null, null);
        assertThatThrownBy(() -> authority.current(workspace, foreign.payload().chatContext().threadId(), foreign.id(), OperationalTool.MYSQL_READ))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        var next = question(workspace, "next", thread(), parent.id());
        assertThatThrownBy(() -> scopes.get(workspace, scope.id())).isInstanceOf(LibraryException.class);
        assertThat(authority.current(workspace, thread(), next.id(), OperationalTool.MYSQL_READ).parentBundleId()).isEqualTo(next.id());
        library.deleteWorkspace(workspace);
        assertThatThrownBy(() -> authority.current(workspace, thread(), next.id(), OperationalTool.MYSQL_READ))
                .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        verifyNoInteractions(mysqlClient);
    }

    @Test void realMetadataDetectsUnobservedNoneConnectedNoneForBothTools() {
        for (var tool : OperationalTool.values()) {
            var scope = open(tool);
            connect(tool);
            disconnect(tool);
            var now = authority.current(workspace, thread(), parent.id(), tool);
            assertThat(now.connectionRevision()).isNull();
            assertThat(now.connectionGeneration()).isNotEqualTo(scope.binding().connectionGeneration());
            assertThatThrownBy(() -> scopes.get(workspace, scope.id())).isInstanceOf(LibraryException.class);
            assertThatThrownBy(() -> scopes.get(workspace, scope.id()))
                    .isInstanceOfSatisfying(LibraryException.class, failure -> assertThat(failure.status()).isEqualTo(404));
        }
    }

    @Test void realConnectionRotationRequiresFreshProposalAndNeverRunsReadTools() {
        for (var tool : OperationalTool.values()) {
            connect(tool);
            var scope = open(tool);
            connect(tool);
            assertThatThrownBy(() -> scopes.revise(workspace, scope.id(), 1, parent.id(), scope.candidate(), Origin.USER_MESSAGE))
                    .isInstanceOf(LibraryException.class);
            var fresh = open(tool);
            assertThat(fresh.binding().connectionRevision()).isNotEqualTo(scope.binding().connectionRevision());
            assertThat(fresh.executionAllowed()).isFalse();
        }
    }
}
