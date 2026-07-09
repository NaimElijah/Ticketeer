package com.ticketing.system.shared.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The bounded retry behind the WSEP reachability handshake (#455 / #365): a cold-starting endpoint may
 * miss early attempts but answer a later one. Exercises {@code withRetry} directly (package-private) so
 * no real HTTP is involved; backoff is 0 to keep the test fast.
 */
class WsepHttpClientRetryTest {

    // attempts=3, backoff=0; base-url/timeout are irrelevant to withRetry (post() is never called here).
    private WsepHttpClient client() {
        return new WsepHttpClient("http://localhost", 1, 3, 0);
    }

    @Test
    void withRetry_succeedsOnALaterAttempt_afterTransientFailures() {
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
    void withRetry_rethrowsAfterExhaustingAttempts() {
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
    void withRetry_doesNotRetryWhenTheFirstAttemptSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = client().withRetry(() -> {
            calls.incrementAndGet();
            return "OK";
        });
        assertEquals("OK", result);
        assertEquals(1, calls.get());
    }
}
