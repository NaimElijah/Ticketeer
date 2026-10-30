package com.ticketing.system.identity.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.LoginAttemptTracker.Admission;
import com.ticketing.system.identity.application.service.LoginAttemptTracker.Pool;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.testutil.AdjustableClock;

/**
 * The sign-in lockout's bookkeeping (SLR.2, #185). Attempts are counted before the password is
 * checked, in a fixed window of {@code lock-minutes} that opens at a key's first attempt. Within the
 * window, attempts beyond the limit are refused; a success clears the key; expired keys are purged on
 * every write, so keys that never come back do not accumulate.
 */
class LoginAttemptTrackerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int MAX_ATTEMPTS = 3;
    private static final long LOCK_MINUTES = 15;
    private static final Duration WINDOW = Duration.ofMinutes(LOCK_MINUTES);
    private static final int USERNAME_COLUMN_LENGTH = 255;
    private static final int THREADS = 16;
    private static final long AWAIT_SECONDS = 10;

    private final AdjustableClock clock = new AdjustableClock(T0);
    private final LoginAttemptTracker tracker =
            new LoginAttemptTracker(new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES), clock);

    @Test
    void GivenNoAttempts_WhenAdmit_ThenAdmittedAsTheFirstAttemptOfAWindowFromNow() {
        Admission admission = tracker.admit(Pool.MEMBER, "alice");

        assertTrue(admission.admitted());
        assertEquals(1, admission.attempts());
        assertEquals(T0.plus(WINDOW), admission.windowEndsAt());
    }

    @Test
    void GivenMaxAttemptsAdmitted_WhenOneMoreArrivesInsideTheWindow_ThenRefused() {
        admitTimes(Pool.MEMBER, "alice", MAX_ATTEMPTS);
        clock.advance(WINDOW.minusSeconds(1));

        Admission admission = tracker.admit(Pool.MEMBER, "alice");

        assertFalse(admission.admitted(), "the attempt past the limit must be refused before verification");
        assertEquals(T0.plus(WINDOW), admission.windowEndsAt(), "the lock lasts until the window ends");
    }

    @Test
    void GivenLimitReached_WhenTheWindowSinceTheFirstAttemptPasses_ThenAdmittedAsAFreshCount() {
        admitTimes(Pool.MEMBER, "alice", MAX_ATTEMPTS + 1);
        clock.advance(WINDOW);

        Admission admission = tracker.admit(Pool.MEMBER, "alice");

        assertTrue(admission.admitted());
        assertEquals(1, admission.attempts());
        assertEquals(T0.plus(WINDOW).plus(WINDOW), admission.windowEndsAt());
    }

    @Test
    void GivenFailedAttempts_WhenCleared_ThenTheCountStartsFromZero() {
        admitTimes(Pool.MEMBER, "alice", MAX_ATTEMPTS);

        tracker.clear(Pool.MEMBER, "alice");

        assertEquals(1, tracker.admit(Pool.MEMBER, "alice").attempts());
    }

    @Test
    void GivenZeroMaxAttempts_WhenAdmittedManyTimes_ThenAlwaysAdmittedAndNothingIsTracked() {
        LoginAttemptTracker disabled = new LoginAttemptTracker(new LockoutProperties(0, LOCK_MINUTES), clock);

        for (int i = 0; i < MAX_ATTEMPTS * 10; i++) {
            assertTrue(disabled.admit(Pool.MEMBER, "alice").admitted(), "max-attempts 0 disables the lockout");
        }
        assertEquals(0, disabled.trackedKeyCount());
    }

    @Test
    void GivenAMemberLockedOut_WhenTheSameNameSignsInAsAdmin_ThenTheAdminAttemptIsAdmitted() {
        admitTimes(Pool.MEMBER, "alice", MAX_ATTEMPTS + 1);

        assertTrue(tracker.admit(Pool.ADMIN, "alice").admitted(), "the two pools are counted apart");
    }

    @Test
    void GivenAttemptsUnderOneNameInOtherCasesAndPadding_WhenAdmit_ThenTheyCountOnOneKey() {
        tracker.admit(Pool.MEMBER, "alice");
        tracker.admit(Pool.MEMBER, "ALICE");
        tracker.admit(Pool.MEMBER, "  Alice  ");

        assertFalse(tracker.admit(Pool.MEMBER, "alice").admitted());
        assertEquals(1, tracker.trackedKeyCount());
    }

    @Test
    void GivenNullUsername_WhenAdmit_ThenCountedLikeAnEmptyOne() {
        tracker.admit(Pool.MEMBER, null);

        assertEquals(2, tracker.admit(Pool.MEMBER, "").attempts());
    }

    @Test
    void GivenUsernamesLongerThanTheColumn_WhenAdmit_ThenTheyShareTheKeyOfTheirFirst255Characters() {
        String prefix = "a".repeat(USERNAME_COLUMN_LENGTH);

        tracker.admit(Pool.MEMBER, prefix + "x".repeat(10_000));
        Admission second = tracker.admit(Pool.MEMBER, prefix + "y".repeat(10_000));

        assertEquals(2, second.attempts(), "a key keeps no more of a username than the column can hold");
        assertEquals(1, tracker.trackedKeyCount());
    }

    @Test
    void GivenAnUntouchedKeyPastItsWindow_WhenAnotherKeyIsWritten_ThenTheUntouchedKeyIsPurged() {
        tracker.admit(Pool.MEMBER, "never.returns");
        clock.advance(WINDOW);

        tracker.admit(Pool.MEMBER, "someone.else");

        assertEquals(1, tracker.trackedKeyCount(), "the expired key must be gone, leaving only the new one");
    }

    @Test
    void GivenAnUntouchedKeyPastItsWindow_WhenAnotherKeyIsCleared_ThenTheUntouchedKeyIsPurged() {
        tracker.admit(Pool.MEMBER, "never.returns");
        clock.advance(WINDOW);

        tracker.clear(Pool.MEMBER, "someone.else");

        assertEquals(0, tracker.trackedKeyCount());
    }

    @Test
    void GivenAnUntouchedKeyInsideItsWindow_WhenAnotherKeyIsWritten_ThenTheUntouchedKeyIsKept() {
        tracker.admit(Pool.MEMBER, "comes.back");
        clock.advance(WINDOW.minusSeconds(1));

        tracker.admit(Pool.MEMBER, "someone.else");

        assertEquals(2, tracker.trackedKeyCount());
    }

    @Test
    void GivenManyAttemptsAtOnce_WhenAdmit_ThenExactlyMaxAttemptsAreAdmitted() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Boolean>> admissions = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                admissions.add(pool.submit(() -> {
                    start.await();
                    return tracker.admit(Pool.MEMBER, "alice").admitted();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> admission : admissions) {
                if (admission.get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }

            assertEquals(MAX_ATTEMPTS, admitted, "the count and the limit check must be one atomic step");
        } finally {
            pool.shutdownNow();
        }
    }

    private void admitTimes(Pool pool, String username, int times) {
        for (int i = 0; i < times; i++) {
            tracker.admit(pool, username);
        }
    }
}
