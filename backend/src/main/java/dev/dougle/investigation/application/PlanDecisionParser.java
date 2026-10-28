package dev.dougle.investigation.application;

import dev.dougle.investigation.domain.PlanDecision;
import dev.dougle.investigation.domain.PendingOperationalScope;
import dev.dougle.security.application.CredentialContentGuard;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
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
            fields(root, Set.of("decision", "query", "candidate"));
            boolean candidatePresent = root.has("candidate") && !root.path("candidate").isNull();
            return switch (root.path("decision").asString()) {
                case "SEARCH" -> {
                    if (candidatePresent || !root.path("query").isString()) throw new IllegalArgumentException();
                    yield new PlanDecision.Search(root.path("query").asString());
                }
                case "STOP" -> {
                    if (candidatePresent || root.has("query") && !root.path("query").isNull()) {
                        throw new IllegalArgumentException();
                    }
                    yield new PlanDecision.Stop();
                }
                case "PROPOSE_MYSQL_READ" -> {
                    if (!root.has("query") || !root.path("query").isNull()) throw new IllegalArgumentException();
                    var tool = PlanDecision.OperationalTool.MYSQL_READ;
                    yield new PlanDecision.Propose(tool, candidatePresent ? candidate(tool, root.path("candidate")) : null);
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid investigation plan decision");
        }
    }

    private PendingOperationalScope.Candidate candidate(PlanDecision.OperationalTool tool, JsonNode node) {
        CredentialContentGuard.requireSafeTree(node);
        if (tool == PlanDecision.OperationalTool.MYSQL_READ) {
            fields(node, Set.of("table", "columns", "equalities", "limit"));
            java.util.Map<String, String> equalities = null;
            if (node.hasNonNull("equalities")) {
                var conditions = node.path("equalities");
                if (!conditions.isArray() || conditions.size() > 16) throw new IllegalArgumentException();
                equalities = new LinkedHashMap<>();
                for (var condition : conditions) {
                    fields(condition, Set.of("column", "value"));
                    String column = text(condition, "column");
                    String value = text(condition, "value");
                    if (column == null || value == null || equalities.containsKey(column)) throw new IllegalArgumentException();
                    CredentialContentGuard.requireSafe(column + "=" + value);
                    equalities.put(column, value);
                }
            }
            return new PendingOperationalScope.Mysql(text(node, "table"), strings(node, "columns"), equalities, integer(node, "limit"));
        }
        throw new IllegalArgumentException("Removed operational tool");
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) throw new IllegalArgumentException();
    }

    private static String text(JsonNode node, String key) {
        if (!node.hasNonNull(key)) return null;
        if (!node.path(key).isString()) throw new IllegalArgumentException();
        return node.path(key).asString();
    }

    private static Integer integer(JsonNode node, String key) {
        if (!node.hasNonNull(key)) return null;
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt()) throw new IllegalArgumentException();
        return node.path(key).intValue();
    }

    private static List<String> strings(JsonNode node, String key) {
        if (!node.hasNonNull(key)) return null;
        var values = node.path(key);
        if (!values.isArray()) throw new IllegalArgumentException();
        var result = new ArrayList<String>();
        for (var value : values) {
            if (!value.isString()) throw new IllegalArgumentException();
            result.add(value.asString());
        }
        return List.copyOf(result);
    }
}
