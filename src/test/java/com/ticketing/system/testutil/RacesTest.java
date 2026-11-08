package com.ticketing.system.testutil;

import static com.ticketing.system.testutil.Races.awaitOrFail;
import static com.ticketing.system.testutil.Races.awaitOrFailWith;
import static com.ticketing.system.testutil.Races.failureOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The race helpers the concurrency suites share (#189): what {@code failureOf} returns for each way a
 * call ends, and how {@code awaitOrFail} and {@code awaitOrFailWith} end at their bound. A zero bound
 * times out at once, so no test here waits.
 */
class RacesTest {

    private static final Duration NO_WAIT = Duration.ZERO;
    private static final Duration LONG_WAIT = Duration.ofSeconds(20);

    // A test that interrupts its own thread must not leave the flag for the next one, whatever it asserted.
    @AfterEach
    void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void GivenACallThatReturns_WhenFailureOf_ThenItIsNull() {
        assertNull(failureOf(() -> "done"));
    }

    @Test
    void GivenAFutureThatFailed_WhenFailureOf_ThenItIsTheTasksOwnException() {
        IllegalStateException failure = new IllegalStateException("refused");

        assertSame(failure, failureOf(CompletableFuture.failedFuture(failure)::get),
                "the ExecutionException is unwrapped to its cause");
    }

    // Pinned, not endorsed: a failed call whose ExecutionException carries no cause is indistinguishable
    // from one that returned. A Future always supplies the cause, so no caller meets this today.
    @Test
    void GivenAnExecutionExceptionWithoutACause_WhenFailureOf_ThenItIsNullAsIfTheCallReturned() {
        assertNull(failureOf(() -> {
            throw new ExecutionException(null);
        }));
    }

    @Test
    void GivenAFutureStillRunningAtTheBound_WhenFailureOf_ThenItIsTheTimeout() {
        CompletableFuture<Object> running = new CompletableFuture<>();

        assertInstanceOf(TimeoutException.class, failureOf(() -> running.get(0, TimeUnit.SECONDS)));
    }

    @Test
    void GivenACallThatIsInterrupted_WhenFailureOf_ThenTheInterruptionIsReturnedNotThrown() {
        InterruptedException interrupted = new InterruptedException("interrupted");

        assertSame(interrupted, failureOf(() -> {
            throw interrupted;
        }));
    }

    @Test
    void GivenACallWhoseAssertionFails_WhenFailureOf_ThenTheAssertionErrorPropagates() {
        AssertionError failed = new AssertionError("expected 1 but was 2");

        AssertionError thrown = assertThrows(AssertionError.class, () -> failureOf(() -> {
            throw failed;
        }));

        assertSame(failed, thrown);
    }

    @Test
    void GivenALatchAtZero_WhenAwaitOrFail_ThenItReturns() {
        assertDoesNotThrow(() -> awaitOrFail(new CountDownLatch(0), NO_WAIT, "the step"));
    }

    @Test
    void GivenALatchAboveZeroAtTheBound_WhenAwaitOrFail_ThenItThrowsNamingTheStep() {
        IllegalStateException timedOut = assertThrows(IllegalStateException.class,
                () -> awaitOrFail(new CountDownLatch(1), NO_WAIT, "arrangement: the first refund reaches the gateway"));

        assertEquals("arrangement: the first refund reaches the gateway, but it never did", timedOut.getMessage());
    }

    @Test
    void GivenAnInterruptedThread_WhenAwaitOrFail_ThenItThrowsInterruptedException() {
        Thread.currentThread().interrupt();

        assertThrows(InterruptedException.class, () -> awaitOrFail(new CountDownLatch(1), LONG_WAIT, "the step"));
    }

    @Test
    void GivenALatchAtZero_WhenAwaitOrFailWith_ThenItReturns() {
        assertDoesNotThrow(() -> awaitOrFailWith(new CountDownLatch(0), NO_WAIT, "the race was never started"));
    }

    @Test
    void GivenALatchAboveZeroAtTheBound_WhenAwaitOrFailWith_ThenItThrowsTheMessageAsGiven() {
        IllegalStateException timedOut = assertThrows(IllegalStateException.class,
                () -> awaitOrFailWith(new CountDownLatch(1), NO_WAIT, "the race was never started"));

        assertEquals("the race was never started", timedOut.getMessage());
    }
}
