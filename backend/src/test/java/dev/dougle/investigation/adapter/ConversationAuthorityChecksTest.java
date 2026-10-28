package dev.dougle.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import dev.dougle.library.application.LibraryException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConversationAuthorityChecksTest {
    @Test
    void blockedCheckTimesOutAndLateCompletionCannotSupplyAuthority() throws Exception {
        try (var checks = new ConversationAuthorityChecks()) {
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var budget = new ConversationAuthorityChecks.Budget(Duration.ofMillis(100), Duration.ofSeconds(1));
            try {
                assertEquals(504, assertThrows(LibraryException.class, () -> checks.call(budget, () -> {
                    try {
                        while (release.getCount() != 0) {
                            try { release.await(); } catch (InterruptedException ignored) { }
                        }
                        return "late";
                    } finally { finished.countDown(); }
                })).status());
                var invoked = new AtomicBoolean();
                assertEquals(504, assertThrows(LibraryException.class, () -> checks.call(budget, () -> {
                    invoked.set(true); return "unexpected";
                })).status());
                assertFalse(invoked.get());
            } finally { release.countDown(); }
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void totalRequestDeadlineDoesNotResetForAnotherCheckpoint() throws Exception {
        try (var checks = new ConversationAuthorityChecks()) {
            var budget = new ConversationAuthorityChecks.Budget(Duration.ofSeconds(1), Duration.ofMillis(50));
            assertEquals("first", checks.call(budget, () -> "first"));
            Thread.sleep(80);
            var invoked = new AtomicBoolean();
            assertEquals(504, assertThrows(LibraryException.class, () -> checks.call(budget, () -> {
                invoked.set(true); return "unexpected";
            })).status());
            assertFalse(invoked.get());
        }
    }

    @Test
    void authorityFailurePropagatesWithoutLeakingOrAuthorizing() {
        try (var checks = new ConversationAuthorityChecks()) {
            var expected = new LibraryException(409, "changed");
            assertSame(expected, assertThrows(LibraryException.class, () -> checks.call(
                    new ConversationAuthorityChecks.Budget(Duration.ofSeconds(1), Duration.ofSeconds(2)), () -> { throw expected; })));
        }
    }
}
