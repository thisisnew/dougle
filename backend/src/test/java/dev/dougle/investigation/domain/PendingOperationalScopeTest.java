package dev.dougle.investigation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PendingOperationalScopeTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private PendingOperationalScope scope(PendingOperationalScope.Candidate candidate, PendingOperationalScope.Origin origin) {
        return new PendingOperationalScope("scope", new PendingOperationalScope.Binding("workspace", "thread", "parent", candidate.tool(), "revision", "generation-one"),
                1, candidate, origin, NOW, NOW.plusSeconds(600));
    }

    @Test void missingSqlFieldsAreInternalAndNullConditionsNeverMeanApprovedUnfilteredRead() {
        var incomplete = scope(new PendingOperationalScope.Mysql(null, null, null, null), PendingOperationalScope.Origin.USER_MESSAGE);
        assertThat(incomplete.missingInternalFields()).containsExactly(PendingOperationalScope.Field.TABLE,
                PendingOperationalScope.Field.COLUMNS, PendingOperationalScope.Field.CONDITIONS, PendingOperationalScope.Field.LIMIT);
        assertThat(incomplete.missingUserContext()).isEmpty();
        var explicit = scope(new PendingOperationalScope.Mysql("orders", List.of("id"), Map.of(), 20), PendingOperationalScope.Origin.USER_MESSAGE);
        assertThat(explicit.state()).isEqualTo(PendingOperationalScope.State.FIELDS_COMPLETE);
        assertThat(explicit.executionAllowed()).isFalse();
    }

    @Test void logTechnicalFieldsNeverBecomeUserFormQuestions() {
        var incomplete = scope(new PendingOperationalScope.Datadog(null, null, null, null, null, null, null), PendingOperationalScope.Origin.MODEL_PROPOSAL);
        assertThat(incomplete.missingInternalFields()).containsExactly(PendingOperationalScope.Field.SERVICE,
                PendingOperationalScope.Field.INDEXES, PendingOperationalScope.Field.LIMIT);
        assertThat(incomplete.missingUserContext()).containsExactly(PendingOperationalScope.UserContext.ENVIRONMENT,
                PendingOperationalScope.UserContext.TIME_WINDOW, PendingOperationalScope.UserContext.TIME_ZONE);
        assertThat(incomplete.executionAllowed()).isFalse();
    }

    @Test void everyOriginIncludingUserMessageRemainsNonAuthorizingWhenFieldsComplete() {
        for (var origin : PendingOperationalScope.Origin.values()) {
            var value = scope(new PendingOperationalScope.Datadog("staging", "billing", Set.of("fixture"), NOW.minusSeconds(60), NOW, "Asia/Seoul", 20), origin);
            assertThat(value.missingFields()).isEmpty();
            assertThat(value.executionAllowed()).isFalse();
            assertThat(value.sessionOnly()).isTrue();
            assertThat(value.toString()).doesNotContain("billing", "staging", "workspace");
        }
    }

    @Test void immutableCandidateCopiesCollectionsAndDoesNotPrintPredicateValues() {
        var columns = new ArrayList<>(List.of("id"));
        var conditions = new HashMap<>(Map.of("id", "sensitive-fixture"));
        var candidate = new PendingOperationalScope.Mysql("orders", columns, conditions, 10);
        columns.add("changed"); conditions.clear();
        assertThat(candidate.columns()).containsExactly("id");
        assertThat(candidate.equalities()).containsEntry("id", "sensitive-fixture");
        assertThatThrownBy(() -> candidate.equalities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(candidate.toString()).doesNotContain("sensitive-fixture", "orders");
    }

    @Test void rejectsSqlExpressionsWildcardsUrlsAndInvalidStructuralLimits() {
        for (String table : List.of("orders;DELETE", "orders()", "schema.orders", "*", "https://host/path")) {
            assertThatThrownBy(() -> new PendingOperationalScope.Mysql(table, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PendingOperationalScope.Mysql("orders", List.of("id", "id"), null, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Mysql("orders", null, Map.of("id", "x".repeat(1025)), 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Mysql("orders", null, null, 201)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Datadog("*", null, null, null, null, null, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Datadog(null, null, null, NOW.minusSeconds(3601), NOW, "UTC", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Datadog(null, null, null, NOW, NOW, "UTC", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Datadog(null, null, null, null, null, "not-a-zone", 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope.Datadog(null, null, null, null, null, null, 51)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void bindingToolExpiryAndClockRollbackFailClosed() {
        var candidate = new PendingOperationalScope.Mysql(null, null, null, null);
        var value = scope(candidate, PendingOperationalScope.Origin.SOURCE_EXCERPT);
        assertThat(value.expiredAt(NOW.plusSeconds(599))).isFalse();
        assertThat(value.expiredAt(NOW.plusSeconds(600))).isTrue();
        assertThat(value.expiredAt(NOW.minusSeconds(1))).isTrue();
        assertThatThrownBy(() -> new PendingOperationalScope("scope", value.binding(), 0, candidate, value.origin(), NOW, NOW.plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope("scope", value.binding(), 1, candidate, value.origin(), NOW, NOW.plusSeconds(601))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingOperationalScope("scope", new PendingOperationalScope.Binding("workspace", "thread", "parent",
                PlanDecision.OperationalTool.DATADOG_LOGS, "revision", "generation-one"), 1, candidate, value.origin(), NOW, NOW.plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void disconnectedBindingStillRequiresLifecycleGeneration() {
        for (String generation : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> new PendingOperationalScope.Binding("workspace", "thread", "parent",
                    PlanDecision.OperationalTool.MYSQL_READ, null, generation)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
