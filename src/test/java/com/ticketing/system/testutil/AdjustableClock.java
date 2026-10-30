package com.ticketing.system.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test helper: a UTC {@link Clock} that stands still until the test moves it, so a token or a
 * session can expire without waiting. Share one instance between every component under test that
 * reads the time, as production shares the one {@code Clock} bean (ADR-008).
 */
public final class AdjustableClock extends Clock {

    private Instant now;

    /** @param start the instant the clock reads until it is first advanced */
    public AdjustableClock(Instant start) {
        this.now = start;
    }

    /** Moves the clock forward by {@code by}. */
    public void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** @throws UnsupportedOperationException always: production reads the time in UTC only */
    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("AdjustableClock is UTC only");
    }

    @Override
    public Instant instant() {
        return now;
    }
}
