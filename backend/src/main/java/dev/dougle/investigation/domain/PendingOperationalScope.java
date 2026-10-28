package dev.dougle.investigation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record PendingOperationalScope(String id, Binding binding, long revision, Candidate candidate, Origin origin,
                                      Instant createdAt, Instant expiresAt) {
    public enum Origin { USER_MESSAGE, MODEL_PROPOSAL, SOURCE_EXCERPT }
    public enum Field { CONNECTION, TABLE, COLUMNS, CONDITIONS, LIMIT, ENVIRONMENT, SERVICE, INDEXES, FROM, TO, TIME_ZONE }
    public enum State { INCOMPLETE, FIELDS_COMPLETE }
    public enum UserContext { ENVIRONMENT, TIME_WINDOW, TIME_ZONE }

    public record Binding(String workspaceId, String threadId, String parentBundleId,
                          PlanDecision.OperationalTool tool, String connectionRevision, String connectionGeneration) {
        public Binding {
            identifier(workspaceId); identifier(threadId); identifier(parentBundleId);
            Objects.requireNonNull(tool);
            if (connectionRevision != null) identifier(connectionRevision);
            identifier(connectionGeneration);
        }
        @Override public String toString() { return "Operational scope binding [redacted]"; }
    }

    public sealed interface Candidate permits Mysql, Datadog {
        PlanDecision.OperationalTool tool();
        List<Field> missingFields();
    }

    public record Mysql(String table, List<String> columns, Map<String, String> equalities, Integer limit) implements Candidate {
        public Mysql {
            if (table != null) sqlIdentifier(table);
            if (columns != null) {
                columns = List.copyOf(columns);
                if (columns.isEmpty() || columns.size() > 16 || new HashSet<>(columns).size() != columns.size()) throw invalid();
                columns.forEach(PendingOperationalScope::sqlIdentifier);
            }
            if (equalities != null) {
                if (equalities.size() > 16) throw invalid();
                equalities = Collections.unmodifiableSortedMap(new TreeMap<>(equalities));
                int characters = 0;
                for (var entry : equalities.entrySet()) {
                    sqlIdentifier(entry.getKey());
                    String value = entry.getValue();
                    if (value == null || value.length() > 1024 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
                    characters += value.length();
                }
                if (characters > 4096) throw invalid();
            }
            if (limit != null && (limit < 1 || limit > 200)) throw invalid();
        }
        @Override public PlanDecision.OperationalTool tool() { return PlanDecision.OperationalTool.MYSQL_READ; }
        @Override public List<Field> missingFields() {
            var missing = new ArrayList<Field>();
            if (table == null) missing.add(Field.TABLE);
            if (columns == null) missing.add(Field.COLUMNS);
            if (equalities == null) missing.add(Field.CONDITIONS);
            if (limit == null) missing.add(Field.LIMIT);
            return List.copyOf(missing);
        }
        @Override public String toString() { return "MySQL scope candidate [redacted]"; }
    }

    public record Datadog(String environment, String service, Set<String> indexes, Instant from, Instant to,
                          String timeZone, Integer limit) implements Candidate {
        public Datadog {
            if (environment != null) tag(environment);
            if (service != null) tag(service);
            if (indexes != null) {
                indexes = Set.copyOf(indexes);
                if (indexes.isEmpty() || indexes.size() > 8) throw invalid();
                indexes.forEach(PendingOperationalScope::tag);
            }
            if (from != null && to != null && (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(1)) > 0)) throw invalid();
            if (timeZone != null) {
                if (timeZone.length() > 64) throw invalid();
                try { ZoneId.of(timeZone); } catch (RuntimeException badZone) { throw invalid(); }
            }
            if (limit != null && (limit < 1 || limit > 50)) throw invalid();
        }
        @Override public PlanDecision.OperationalTool tool() { return PlanDecision.OperationalTool.DATADOG_LOGS; }
        @Override public List<Field> missingFields() {
            var missing = new ArrayList<Field>();
            if (environment == null) missing.add(Field.ENVIRONMENT);
            if (service == null) missing.add(Field.SERVICE);
            if (indexes == null) missing.add(Field.INDEXES);
            if (from == null) missing.add(Field.FROM);
            if (to == null) missing.add(Field.TO);
            if (timeZone == null) missing.add(Field.TIME_ZONE);
            if (limit == null) missing.add(Field.LIMIT);
            return List.copyOf(missing);
        }
        @Override public String toString() { return "Datadog scope candidate [redacted]"; }
    }

    public PendingOperationalScope {
        identifier(id);
        Objects.requireNonNull(binding); Objects.requireNonNull(candidate); Objects.requireNonNull(origin);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(expiresAt);
        if (revision < 1 || candidate.tool() != binding.tool() || !createdAt.isBefore(expiresAt)
                || Duration.between(createdAt, expiresAt).compareTo(Duration.ofMinutes(10)) > 0) throw invalid();
    }

    public List<Field> missingFields() {
        var fields = new ArrayList<Field>();
        if (binding.connectionRevision() == null) fields.add(Field.CONNECTION);
        fields.addAll(candidate.missingFields());
        return List.copyOf(fields);
    }
    public State state() { return missingFields().isEmpty() ? State.FIELDS_COMPLETE : State.INCOMPLETE; }
    public List<Field> missingInternalFields() {
        return missingFields().stream().filter(field -> field != Field.ENVIRONMENT && field != Field.FROM
                && field != Field.TO && field != Field.TIME_ZONE).toList();
    }
    public List<UserContext> missingUserContext() {
        var missing = missingFields();
        var topics = new ArrayList<UserContext>();
        if (missing.contains(Field.ENVIRONMENT)) topics.add(UserContext.ENVIRONMENT);
        if (missing.contains(Field.FROM) || missing.contains(Field.TO)) topics.add(UserContext.TIME_WINDOW);
        if (missing.contains(Field.TIME_ZONE)) topics.add(UserContext.TIME_ZONE);
        return List.copyOf(topics);
    }
    public boolean executionAllowed() { return false; }
    public boolean sessionOnly() { return true; }
    public boolean expiredAt(Instant now) { return Objects.requireNonNull(now).isBefore(createdAt) || !now.isBefore(expiresAt); }
    @Override public String toString() { return "Pending operational scope [redacted; execution forbidden]"; }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
    }
    private static void sqlIdentifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw invalid();
    }
    private static void tag(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid operational scope candidate"); }
}
