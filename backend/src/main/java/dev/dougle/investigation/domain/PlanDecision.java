package dev.dougle.investigation.domain;

public sealed interface PlanDecision {
    enum OperationalTool { MYSQL_READ, DATADOG_LOGS }

    record Propose(OperationalTool tool, PendingOperationalScope.Candidate candidate) implements PlanDecision {
        public Propose {
            java.util.Objects.requireNonNull(tool);
            if (candidate != null && candidate.tool() != tool) throw new IllegalArgumentException("Mismatched candidate tool");
        }
        public Propose(OperationalTool tool) { this(tool, null); }
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
