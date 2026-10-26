package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.PlanDecision;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class PlanDecisionParserTest {
    private final PlanDecisionParser parser = new PlanDecisionParser();

    @Test void typedCandidatesUseBoundedDataWithoutClientAuthorityAndPreserveNulls() {
        var parsed = (PlanDecision.Propose) parser.parse("""
                {"decision":"PROPOSE_MYSQL_READ","query":null,"candidate":{
                  "table":"orders","columns":["id"],"equalities":[{"column":"id","value":"fixture"}],"limit":20}}
                """);
        assertEquals(new dev.knowledgeforge.investigation.domain.PendingOperationalScope.Mysql(
                "orders", java.util.List.of("id"), java.util.Map.of("id", "fixture"), 20), parsed.candidate());
        var logs = (PlanDecision.Propose) parser.parse("""
                {"decision":"PROPOSE_DATADOG_LOGS","query":null,"candidate":{
                  "environment":null,"service":null,"indexes":null,"from":null,"to":null,"timeZone":null,"limit":null}}
                """);
        assertEquals(7, logs.candidate().missingFields().size());
        assertEquals(new PlanDecision.Stop(), parser.parse("{\"decision\":\"STOP\",\"query\":null,\"candidate\":null}"));
    }

    @Test void rejectsCandidateInjectionWrongTypesExpressionsDuplicatesAndCredentialAssignments() {
        for (String candidate : java.util.List.of(
                "{\"sql\":\"SELECT 1\"}", "{\"table\":\"orders;DELETE\"}", "{\"url\":\"https://invalid\"}",
                "{\"connectionGeneration\":\"client\"}", "{\"approved\":true}", "{\"limit\":1.5}",
                "{\"limit\":\"20\"}", "{\"limit\":2147483648}", "{\"columns\":[null]}",
                "{\"equalities\":{\"id\":\"fixture\"}}", "{\"table\":\"first\",\"table\":\"second\"}",
                "{\"equalities\":[{\"column\":\"id\",\"value\":\"one\"},{\"column\":\"id\",\"value\":\"two\"}]}",
                "{\"equalities\":[{\"column\":\"password\",\"value\":\"SyntheticSecret123456789\"}]}")) {
            rejects("{\"decision\":\"PROPOSE_MYSQL_READ\",\"query\":null,\"candidate\":" + candidate + "}");
        }
        rejects("{\"decision\":\"SEARCH\",\"query\":\"fixture\",\"candidate\":{}}");
        rejects("{\"decision\":\"PROPOSE_DATADOG_LOGS\",\"query\":null,\"candidate\":{\"table\":\"orders\"}}");
        rejects("{\"decision\":\"PROPOSE_DATADOG_LOGS\",\"query\":null,\"candidate\":{\"from\":\"yesterday\"}}");
    }

    @Test
    void operationalProposalsHaveNoScopeAndRetainStrictOutputLimits() {
        for (var tool : PlanDecision.OperationalTool.values()) {
            String proposal = "{\"decision\":\"PROPOSE_" + tool.name() + "\",\"query\":null}";
            assertEquals(new PlanDecision.Propose(tool), parser.parse(proposal));
            rejects(proposal.replace("null", "\"production\""));
            rejects(proposal.replace(",\"query\":null", ""));
            rejects(proposal.replace("}", ",\"scope\":\"SELECT secrets\"}"));
            rejects(proposal.replace("}", ",\"query\":null}"));
            rejects(proposal + " {}");
            rejects(proposal + " ".repeat(4096));
        }
    }

    @Test
    void acceptsOnlyTheAgreedDecisionAndQueryContract() {
        assertEquals(new PlanDecision.Stop(), parser.parse("{\"decision\":\"STOP\"}"));
        assertEquals(new PlanDecision.Stop(), parser.parse("{\"decision\":\"STOP\",\"query\":null}"));
        assertEquals(new PlanDecision.Search("입하 완료 조건"),
                parser.parse(" \n{\"query\":\" 입하 완료 조건 \",\"decision\":\"SEARCH\"}\t\r\n"));
        assertEquals(new PlanDecision.Search("Strategy"),
                parser.parse("{\"decis\\u0069on\":\"SEARCH\",\"query\":\"Strategy\"}"));
    }

    @Test
    void boundsTheOriginalQueryBeforeTrimming() {
        assertEquals(new PlanDecision.Search("x"), parser.parse(search("x")));
        assertEquals(new PlanDecision.Search("가".repeat(256)), parser.parse(search("가".repeat(256))));
        assertEquals(new PlanDecision.Search("😀".repeat(128)), parser.parse(search("😀".repeat(128))));
        rejects(search("x".repeat(257)));
        rejects(search(" ".repeat(256) + "x"));
        rejects(search("😀".repeat(129)));
        rejects("{\"decision\":\"SEARCH\",\"query\":\"" + "\\u0078".repeat(257) + "\"}");
    }

    @Test
    void enforcesUtf8OutputBytesIncludingWhitespaceBeforeParsing() {
        String stop = "{\"decision\":\"STOP\"}";
        String boundary = stop + " ".repeat(PlanDecisionParser.MAX_OUTPUT_BYTES - stop.length());
        assertEquals(4096, boundary.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(new PlanDecision.Stop(), parser.parse(boundary));
        rejects(boundary + " ");

        String query = search("가".repeat(256));
        String multibyteBoundary = query + " ".repeat(4096 - query.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(new PlanDecision.Search("가".repeat(256)), parser.parse(multibyteBoundary));
        assertTrue((multibyteBoundary + " ").length() < 4096);
        rejects(multibyteBoundary + " ");
        rejects(" ".repeat(100000));
    }

    @ParameterizedTest
    @MethodSource("invalidOutputs")
    void rejectsMalformedOrUnauthorizedDecisionsWithoutEchoingInput(String output) {
        rejects(output);
    }

    static Stream<String> invalidOutputs() {
        return Stream.of(
                null, "", " \n\t ", "null", "true", "1", "\"STOP\"", "[]", "{}",
                "[{\"decision\":\"STOP\"}]",
                "{\"decision\":null}", "{\"decision\":true}", "{\"decision\":1}",
                "{\"decision\":[\"STOP\"]}", "{\"decision\":{\"value\":\"STOP\"}}",
                "{\"decision\":\"stop\"}", "{\"decision\":\" STOP \"}",
                "{\"action\":\"SEARCH\",\"question\":\"Strategy\"}",
                "{\"decision\":\"SEARCH\"}",
                "{\"decision\":\"SEARCH\",\"query\":null}",
                "{\"decision\":\"SEARCH\",\"query\":123}",
                "{\"decision\":\"SEARCH\",\"query\":false}",
                "{\"decision\":\"SEARCH\",\"query\":[\"Strategy\"]}",
                "{\"decision\":\"SEARCH\",\"query\":{\"text\":\"Strategy\"}}",
                search(""), search("   "), "{\"decision\":\"SEARCH\",\"query\":\"\\n\\t\"}",
                "{\"decision\":\"SEARCH\",\"query\":\"\\uD800\"}",
                "{\"decision\":\"SEARCH\",\"query\":\"\\uDC00\"}",
                "{\"decision\":\"STOP\",\"reason\":\"Need clarification\"}",
                "{\"decision\":\"STOP\",\"query\":\"Strategy\"}",
                "{\"decision\":\"STOP\",\"query\":false}",
                "{\"decision\":\"STOP\",\"query\":{}}",
                "{\"decision\":\"STOP\",\"query\":null,\"extra\":null}",
                "{\"decision\":\"STOP\",\"query\":null,\"query\":null}",
                "{\"decision\":\"SEARCH\",\"query\":\"Strategy\",\"extra\":null}",
                "{\"decision\":\"SEARCH\",\"query\":\"Strategy\",\"workspaceId\":\"other\"}",
                "{\"decision\":\"SEARCH\",\"query\":\"Strategy\",\"approved\":true}",
                "{\"decision\":\"SEARCH\",\"query\":\"Strategy\",\"tool\":\"shell\"}",
                "{\"decision\":\"SHELL\",\"command\":\"echo PRIVATE_MARKER\"}",
                "{\"decision\":\"FETCH\",\"url\":\"https://example.invalid/PRIVATE_MARKER\"}",
                "{\"decision\":\"local.workspace.search\",\"query\":\"Strategy\"}",
                "{\"decision\":\"STOP\",\"decision\":\"STOP\"}",
                "{\"decision\":\"STOP\",\"decision\":\"SEARCH\",\"query\":\"Strategy\"}",
                "{\"decision\":\"SEARCH\",\"query\":\"first\",\"query\":\"second\"}",
                "{\"decision\":\"SEARCH\",\"query\":\"first\",\"qu\\u0065ry\":\"second\"}",
                "{\"decision\":\"STOP\"}{\"decision\":\"STOP\"}",
                "{\"decision\":\"STOP\"} null", "{\"decision\":\"STOP\"} PRIVATE_MARKER",
                "```json\n{\"decision\":\"STOP\"}\n```",
                "PRIVATE_MARKER {\"decision\":\"STOP\"}",
                "{\"decision\":\"STOP\",}", "{'decision':'STOP'}", "{decision:\"STOP\"}",
                "{/* PRIVATE_MARKER */\"decision\":\"STOP\"}",
                "{\"decision\":\"STOP\"}// PRIVATE_MARKER", "{\"decision\":\"STOP\"",
                "{\"decision\":\"SEARCH\",\"query\":\"bad\ntext\"}");
    }

    @Test
    void shellAndUrlTextRemainLiteralSearchDataNotExecutableActions() {
        String query = "$(echo PRIVATE_MARKER); https://example.invalid/fixture";
        assertEquals(new PlanDecision.Search(query), parser.parse(search(query)));
    }

    @Test
    void domainSearchCannotBypassQueryValidation() {
        assertThrows(IllegalArgumentException.class, () -> new PlanDecision.Search(null));
        assertThrows(IllegalArgumentException.class, () -> new PlanDecision.Search(" "));
        assertThrows(IllegalArgumentException.class, () -> new PlanDecision.Search("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new PlanDecision.Search("\uD800"));
        assertEquals("Strategy", new PlanDecision.Search(" Strategy ").query());
    }

    private void rejects(String output) {
        var exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(output));
        assertEquals("Invalid investigation plan decision", exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
    }

    private static String search(String query) {
        return "{\"decision\":\"SEARCH\",\"query\":\"" + query + "\"}";
    }
}
