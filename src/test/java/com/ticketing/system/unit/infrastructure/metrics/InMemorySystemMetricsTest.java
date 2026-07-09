package com.ticketing.system.unit.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.shared.metrics.InMemorySystemMetrics;

/**
 * Unit tests for the in-memory {@link InMemorySystemMetrics} adapter (UC-46).
 * A {@link MutableClock} drives time so windowed counts are deterministic.
 */
class InMemorySystemMetricsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void countWindow_onlyCountsEventsInsideTheWindow() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);

        metrics.record(MetricType.VISITOR_ENTRY);
        metrics.record(MetricType.VISITOR_ENTRY);

        clock.plusSeconds(600); // +10 min
        metrics.record(MetricType.VISITOR_ENTRY);

        assertEquals(3, metrics.total(MetricType.VISITOR_ENTRY));
        assertEquals(1, metrics.count(MetricType.VISITOR_ENTRY, Duration.ofMinutes(5)));   // only the +10min one
        assertEquals(3, metrics.count(MetricType.VISITOR_ENTRY, Duration.ofMinutes(20)));  // all three
    }

    @Test
    void typesAreIndependent_andUnseenTypeIsZero() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);

        metrics.record(MetricType.RESERVATION);

        assertEquals(1, metrics.total(MetricType.RESERVATION));
        assertEquals(0, metrics.total(MetricType.REGISTRATION));
        assertEquals(0, metrics.count(MetricType.VISITOR_EXIT, Duration.ofHours(1)));
    }

    @Test
    void longGap_windowCountReflectsOnlyRecentEvents_totalStaysCumulative() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);

        metrics.record(MetricType.REGISTRATION);
        clock.plusSeconds(3 * 3600); // +3h (beyond retention) then a fresh event
        metrics.record(MetricType.REGISTRATION);

        assertEquals(1, metrics.count(MetricType.REGISTRATION, Duration.ofMinutes(5)));
        assertEquals(2, metrics.total(MetricType.REGISTRATION));
    }

    /** Minimal advanceable Clock so windowed-count assertions are deterministic. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void plusSeconds(long seconds) {
            this.now = this.now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
