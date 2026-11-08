package com.ticketing.system.shared.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.config.WsepProperties;

/**
 * The bounded retry behind the WSEP reachability handshake (#455 / #365): a cold-starting endpoint may
 * miss early attempts but answer a later one. Exercises {@code withRetry} directly (package-private) so
 * no real HTTP is involved; backoff is 0 to keep the tests fast, except where the backoff is under test.
 */
class WsepHttpClientRetryTest {

    // Far longer than WAIT_LIMIT, so only an honoured interrupt can end the backoff within it.
    private static final long LONG_BACKOFF_MS = 60_000;
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    // attempts=3, backoff=0; base-url/timeout are irrelevant to withRetry (post() is never called here).
    private WsepHttpClient client() {
        return new WsepHttpClient(new WsepProperties("http://localhost", 1, 3, 0));
    }

    @Test
    void GivenTransientFailures_WhenWithRetry_ThenSucceedsOnALaterAttempt() {
        AtomicInteger calls = new AtomicInteger();
        String result = client().withRetry(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new WsepCommunicationException("cold-start timeout", new RuntimeException());
            }
            return "OK";
        });
        assertEquals("OK", result);
        assertEquals(3, calls.get(), "should retry until the endpoint finally answers");
    }

    @Test
    void GivenAnEndpointThatStaysDown_WhenWithRetry_ThenRethrowsAfterExhaustingAttempts() {
        AtomicInteger calls = new AtomicInteger();
        WsepCommunicationException ex = assertThrows(WsepCommunicationException.class,
                () -> client().withRetry(() -> {
                    calls.incrementAndGet();
                    throw new WsepCommunicationException("still down", new RuntimeException());
                }));
        assertEquals(3, calls.get(), "should try exactly maxAttempts times");
        assertEquals("still down", ex.getMessage());
    }

    @Test
    void GivenASuccessfulFirstAttempt_WhenWithRetry_ThenDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        String result = client().withRetry(() -> {
            calls.incrementAndGet();
            return "OK";
        });
        assertEquals("OK", result);
        assertEquals(1, calls.get());
    }

    @Test
    void GivenANonWsepException_WhenWithRetry_ThenNoRetry() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException bug = new IllegalStateException("not a transport failure");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> client().withRetry(() -> {
            calls.incrementAndGet();
            throw bug;
        }));

        assertSame(bug, thrown, "a failure that is not WSEP's must reach the caller unchanged");
        assertEquals(1, calls.get(), "only a missing WSEP reply is worth retrying, never a bug");
    }

    @Test
    void GivenZeroAttempts_WhenWithRetry_ThenOneAttempt() {
        WsepHttpClient client = new WsepHttpClient(new WsepProperties("http://localhost", 1, 0, 0));
        AtomicInteger calls = new AtomicInteger();
        WsepCommunicationException down = new WsepCommunicationException("down", new RuntimeException());

        WsepCommunicationException thrown = assertThrows(WsepCommunicationException.class,
                () -> client.withRetry(() -> {
                    calls.incrementAndGet();
                    throw down;
                }));

        assertSame(down, thrown, "the one attempt's failure must be rethrown");
        assertEquals(1, calls.get(), "zero attempts, which binding refuses, must still make exactly one attempt");
    }

    @Test
    void GivenAnInterruptDuringBackoff_WhenWithRetry_ThenTheFlagIsRestored() {
        WsepHttpClient client = new WsepHttpClient(new WsepProperties("http://localhost", 1, 2, LONG_BACKOFF_MS));

        AtomicInteger calls = new AtomicInteger();

        assertTimeoutPreemptively(WAIT_LIMIT, () -> {
            assertThrows(WsepCommunicationException.class, () -> client.withRetry(() -> {
                // Interrupted as the first attempt fails, so the backoff that follows starts interrupted. Only
                // then: a later attempt that set the flag again would hide a backoff that cleared it.
                if (calls.incrementAndGet() == 1) {
                    Thread.currentThread().interrupt();
                }
                throw new WsepCommunicationException("cold-start timeout", new RuntimeException());
            }));

            assertTrue(Thread.interrupted(),
                    "the backoff must restore the interrupt it consumed, so the caller's thread can still stop");
        });
    }
}
