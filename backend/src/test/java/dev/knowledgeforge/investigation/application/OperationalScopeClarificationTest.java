package dev.knowledgeforge.investigation.application;

import dev.knowledgeforge.investigation.domain.PendingOperationalScope;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Binding;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Mysql;
import dev.knowledgeforge.investigation.domain.PendingOperationalScope.Origin;
import dev.knowledgeforge.investigation.domain.PlanDecision.OperationalTool;
import dev.knowledgeforge.library.application.LibraryException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(8)
class OperationalScopeClarificationTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final MutableClock clock = new MutableClock();
    private final OperationalScopeClarification.Authority authority = mock(OperationalScopeClarification.Authority.class);
    private final AtomicReference<String> connection = new AtomicReference<>("revision-one");
    private final AtomicReference<String> generation = new AtomicReference<>("generation-one");
    private final AtomicReference<String> parent = new AtomicReference<>("parent-one");
    private OperationalScopeClarification service;

    @BeforeEach void prepare() {
        doAnswer(call -> {
            if (!parent.get().equals(call.getArgument(2))) throw new LibraryException(409, "Synthetic stale parent");
            return new Binding(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), connection.get(), generation.get());
        }).when(authority).current(anyString(), anyString(), anyString(), any());
        service = new OperationalScopeClarification(authority, clock);
    }

    private PendingOperationalScope open() {
        return service.open("workspace", "thread", parent.get(), new Mysql(null, null, null, null), Origin.MODEL_PROPOSAL);
    }

    @Test void explicitWholeReplacementUsesRevisionCasAndDoesNotExtendExpiryOrInheritOldTarget() {
        var first = open();
        clock.now = NOW.plusSeconds(300);
        parent.set("parent-two");
        var revised = service.revise("workspace", first.id(), 1, "parent-two",
                new Mysql("orders", List.of("id"), Map.of("id", "fixture"), 10), Origin.USER_MESSAGE);
        assertThat(revised.revision()).isEqualTo(2);
        assertThat(revised.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(revised.binding().parentBundleId()).isEqualTo("parent-two");
        assertThat(revised.executionAllowed()).isFalse();
        assertThatThrownBy(() -> service.revise("workspace", first.id(), 1, "parent-two", new Mysql(null, null, null, null), Origin.SOURCE_EXCERPT))
                .isInstanceOf(LibraryException.class);
        var cleared = service.revise("workspace", first.id(), 2, "parent-two", new Mysql(null, null, null, null), Origin.USER_MESSAGE);
        assertThat(cleared.candidate()).isEqualTo(new Mysql(null, null, null, null));
        assertThat(cleared.missingUserContext()).isEmpty();
        assertThat(cleared.missingInternalFields()).contains(PendingOperationalScope.Field.TABLE);
    }

    @Test void nullToConnectedRequiresNewProposalAndNeverSilentlyElevatesOldCandidate() {
        connection.set(null);
        var first = open();
        assertThat(first.missingFields()).contains(PendingOperationalScope.Field.CONNECTION);
        connection.set("revision-connected");
        assertThatThrownBy(() -> service.get("workspace", first.id())).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> service.revise("workspace", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE))
                .isInstanceOf(LibraryException.class);
        var fresh = open();
        assertThat(fresh.id()).isNotEqualTo(first.id());
        assertThat(fresh.binding().connectionRevision()).isEqualTo("revision-connected");
        assertThat(fresh.executionAllowed()).isFalse();
    }

    @Test void connectionRotationRevocationAndStaleParentRejectExistingCandidate() {
        var first = open();
        for (String revision : new String[] {"revision-two", null}) {
            connection.set(revision);
            assertThatThrownBy(() -> service.get("workspace", first.id())).isInstanceOf(LibraryException.class);
        }
        connection.set("revision-one"); parent.set("parent-two");
        assertThatThrownBy(() -> service.get("workspace", first.id())).isInstanceOf(LibraryException.class);
    }

    @Test void workspaceIsolationAndRestartAreFailClosed() {
        var first = open();
        assertThatThrownBy(() -> service.get("other", first.id()))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
        assertThatThrownBy(() -> service.cancel("other", first.id(), 1)).isInstanceOf(LibraryException.class);
        assertThat(new OperationalScopeClarification(authority, clock)).satisfies(restarted ->
                assertThatThrownBy(() -> restarted.get("workspace", first.id())).isInstanceOf(LibraryException.class));
        assertThat(service.get("workspace", first.id())).isEqualTo(first);
    }

    @Test void expiryDuringAuthorityValidationCannotReturnOrReviseExpiredSnapshot() {
        var first = open();
        doAnswer(call -> {
            clock.now = first.expiresAt();
            return first.binding();
        }).when(authority).current(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> service.get("workspace", first.id())).hasMessageContaining("만료");
        assertThatThrownBy(() -> service.revise("workspace", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE))
                .isInstanceOf(LibraryException.class);
    }

    @Test void capacityCancelAndExpiredEntryCleanupAreBounded() {
        var first = open();
        for (int count = 1; count < 64; count++) open();
        assertThatThrownBy(this::open).hasMessageContaining("너무 많습니다");
        assertThatThrownBy(() -> service.cancel("workspace", first.id(), 2)).isInstanceOf(LibraryException.class);
        service.cancel("workspace", first.id(), 1);
        assertThat(open().executionAllowed()).isFalse();
        clock.now = NOW.plusSeconds(600);
        assertThat(open().createdAt()).isEqualTo(clock.now);
    }

    @Test void concurrentCorrectionsCannotOverwriteEachOtherAndAuthorityDoesNotHoldStateLock() throws Exception {
        var first = open();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic deadline");
            return first.binding();
        }).when(authority).current(anyString(), anyString(), anyString(), any());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var left = tasks.submit(() -> catchThrowable(() -> service.revise("workspace", first.id(), 1, "parent-one",
                        new Mysql("left_table", null, null, null), Origin.USER_MESSAGE)));
                var right = tasks.submit(() -> catchThrowable(() -> service.revise("workspace", first.id(), 1, "parent-one",
                        new Mysql("right_table", null, null, null), Origin.MODEL_PROPOSAL)));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                var failures = java.util.Arrays.asList(left.get(2, TimeUnit.SECONDS), right.get(2, TimeUnit.SECONDS));
                assertThat(failures).filteredOn(java.util.Objects::nonNull).singleElement().isInstanceOf(LibraryException.class);
                assertThat(service.get("workspace", first.id()).revision()).isEqualTo(2);
            } finally { release.countDown(); }
        }
    }

    @Test void authorityCannotSubstituteWorkspaceThreadParentOrTool() {
        for (Binding invalid : List.of(new Binding("other", "thread", "parent-one", OperationalTool.MYSQL_READ, "revision-one", "generation-one"),
                new Binding("workspace", "other", "parent-one", OperationalTool.MYSQL_READ, "revision-one", "generation-one"),
                new Binding("workspace", "thread", "other", OperationalTool.MYSQL_READ, "revision-one", "generation-one"),
                new Binding("workspace", "thread", "parent-one", OperationalTool.DATADOG_LOGS, "revision-one", "generation-one"))) {
            doReturn(invalid).when(authority).current(anyString(), anyString(), anyString(), any());
            assertThatThrownBy(this::open).isInstanceOf(LibraryException.class);
        }
    }

    @Test void recognizableCredentialsAndInvalidAbsoluteWindowsAreRejectedBeforeAuthorityCall() {
        clearInvocations(authority);
        assertThatThrownBy(() -> service.open("workspace", "thread", "parent-one",
                new Mysql("orders", List.of("id"), Map.of("password", "SyntheticSecret123456789"), 10), Origin.USER_MESSAGE))
                .isInstanceOf(LibraryException.class).hasMessageNotContaining("SyntheticSecret");
        assertThatThrownBy(() -> service.open("workspace", "thread", "parent-one",
                new PendingOperationalScope.Datadog(null, null, null, NOW.minusSeconds(604801), null, null, null), Origin.MODEL_PROPOSAL))
                .isInstanceOf(LibraryException.class);
        verifyNoInteractions(authority);
    }

    @Test void observedConnectionMismatchPermanentlyInvalidatesGetAndReviseEvenAfterReturnToNull() {
        for (boolean reading : List.of(true, false)) {
            connection.set(null);
            var first = open();
            connection.set("connected");
            assertThatThrownBy(() -> {
                if (reading) service.get("workspace", first.id());
                else service.revise("workspace", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE);
            }).isInstanceOf(LibraryException.class);
            connection.set(null);
            assertThatThrownBy(() -> service.get("workspace", first.id()))
                    .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
            assertThat(open().id()).isNotEqualTo(first.id());
        }
    }

    @Test void generationDetectsUnobservedDisconnectedAndConnectedAba() {
        for (String revision : new String[] {null, "revision-one"}) {
            for (boolean reading : List.of(true, false)) {
                connection.set(revision);
                generation.set("before-transition");
                var first = open();
                connection.set("intermediate");
                generation.set("intermediate-generation");
                connection.set(revision);
                generation.set("after-transition");
                assertThatThrownBy(() -> {
                    if (reading) service.get("workspace", first.id());
                    else service.revise("workspace", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE);
                }).isInstanceOf(LibraryException.class);
                assertThatThrownBy(() -> service.get("workspace", first.id()))
                        .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
            }
        }
    }

    @Test void authorityStaleAndMissingPermanentlyInvalidateButForeignWorkspaceCannot() {
        for (int status : List.of(404, 409)) {
            prepare();
            var first = open();
            doThrow(new LibraryException(status, "synthetic authority denial")).when(authority)
                    .current(anyString(), anyString(), anyString(), any());
            clearInvocations(authority);
            assertThatThrownBy(() -> service.get("other", first.id())).isInstanceOf(LibraryException.class);
            assertThatThrownBy(() -> service.revise("other", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE))
                    .isInstanceOf(LibraryException.class);
            verifyNoInteractions(authority);
            assertThatThrownBy(() -> service.get("workspace", first.id())).isInstanceOf(LibraryException.class);
            doReturn(first.binding()).when(authority).current(anyString(), anyString(), anyString(), any());
            assertThatThrownBy(() -> service.get("workspace", first.id()))
                    .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(404));
        }
    }

    @Test void delayedAuthorityFailureCannotRemoveNewerRevision() throws Exception {
        var first = open();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if (firstCall.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic deadline");
                throw new LibraryException(409, "synthetic delayed stale result");
            }
            return first.binding();
        }).when(authority).current(anyString(), anyString(), anyString(), any());
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var oldRead = tasks.submit(() -> catchThrowable(() -> service.get("workspace", first.id())));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var updated = service.revise("workspace", first.id(), 1, "parent-one", first.candidate(), Origin.USER_MESSAGE);
                release.countDown();
                assertThat(oldRead.get(2, TimeUnit.SECONDS)).isInstanceOf(LibraryException.class);
                assertThat(service.get("workspace", first.id())).isSameAs(updated);
            } finally { release.countDown(); }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
