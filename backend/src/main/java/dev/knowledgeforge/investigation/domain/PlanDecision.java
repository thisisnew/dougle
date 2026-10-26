package dev.knowledgeforge.investigation.domain;

public sealed interface PlanDecision {
    enum OperationalTool { MYSQL_READ, DATADOG_LOGS }

    record Propose(OperationalTool tool) implements PlanDecision {
        public Propose { java.util.Objects.requireNonNull(tool); }
    }

    record Search(String query) implements PlanDecision {
        public Search {
            if (query == null || query.isBlank() || query.length() > 256
                    || query.codePoints().anyMatch(value -> value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE)) {
                throw new IllegalArgumentException("Invalid search query");
            }
            query = query.strip();
        }
    }

    record Stop() implements PlanDecision {}
}
