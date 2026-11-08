package com.ticketing.system.testutil;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Test helper for race tests that hold one thread at a latch while another runs: wait for a step of
 * the arrangement within a bound, so a step that never happens fails the test instead of hanging it,
 * and read what a call threw as a value, so the test releases every held thread before it asserts.
 */
public final class Races {

    private static final String NEVER_HAPPENED = ", but it never did";

    private Races() {
    }

    /**
     * Runs a call and returns what it threw.
     *
     * <p>An {@link ExecutionException}, as a {@code Future}'s {@code get} throws, is unwrapped to its
     * cause, and returned as it is when it has none, so a failure never reads as a return. Any other {@link Exception} is returned as it is, a
     * {@code get}'s {@link java.util.concurrent.TimeoutException} and an {@link InterruptedException}
     * included, and the thread's interrupt flag is left cleared. An {@link Error}, such as a failed
     * assertion inside the call, propagates.
     *
     * @param call the call to run, typically {@code () -> future.get(bound, unit)}
     * @return what the call threw, unwrapped as above; {@code null} when it returned
     */
    public static Throwable failureOf(Callable<?> call) {
        try {
            call.call();
            return null;
        } catch (ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * Waits until a latch reaches zero, for at most {@code bound}.
     *
     * @param latch the latch the awaited step counts down
     * @param bound the longest wait
     * @param what the awaited step, which starts the timeout's message
     * @throws IllegalStateException when the bound passes with the latch above zero, with the message
     *         {@code what + ", but it never did"}
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public static void awaitOrFail(CountDownLatch latch, Duration bound, String what) throws InterruptedException {
        awaitOrFailWith(latch, bound, what + NEVER_HAPPENED);
    }

    /**
     * Waits until a latch reaches zero, for at most {@code bound}, as {@link #awaitOrFail} does, for a
     * caller whose timeout message is not {@code what + ", but it never did"}.
     *
     * @param latch the latch the awaited step counts down
     * @param bound the longest wait
     * @param timeoutMessage the whole message of the timeout
     * @throws IllegalStateException with {@code timeoutMessage} when the bound passes with the latch above zero
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public static void awaitOrFailWith(CountDownLatch latch, Duration bound, String timeoutMessage)
            throws InterruptedException {
        if (!latch.await(bound.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new IllegalStateException(timeoutMessage);
        }
    }
}
