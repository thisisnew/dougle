package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.PlanDecision;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class PlanDecisionParser {
    public static final int MAX_OUTPUT_BYTES = 4096;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public PlanDecision parse(String output) {
        try {
            if (output == null || output.length() > MAX_OUTPUT_BYTES
                    || output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
                throw new IllegalArgumentException();
            }
            var root = json.readTree(output);
            if (root == null || !root.isObject() || !root.path("decision").isString()) {
                throw new IllegalArgumentException();
            }
            return switch (root.path("decision").asString()) {
                case "SEARCH" -> {
                    if (root.size() != 2 || !root.path("query").isString()) throw new IllegalArgumentException();
                    yield new PlanDecision.Search(root.path("query").asString());
                }
                case "STOP" -> {
                    if (root.size() != 1 && !(root.size() == 2 && root.has("query") && root.path("query").isNull())) {
                        throw new IllegalArgumentException();
                    }
                    yield new PlanDecision.Stop();
                }
                case "PROPOSE_MYSQL_READ", "PROPOSE_DATADOG_LOGS" -> {
                    if (root.size() != 2 || !root.has("query") || !root.path("query").isNull()) throw new IllegalArgumentException();
                    yield new PlanDecision.Propose(root.path("decision").asString().equals("PROPOSE_MYSQL_READ")
                            ? PlanDecision.OperationalTool.MYSQL_READ : PlanDecision.OperationalTool.DATADOG_LOGS);
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid investigation plan decision");
        }
    }
}
