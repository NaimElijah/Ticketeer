package com.ticketing.system.unit.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.shared.metrics.InMemorySystemMetrics;

/**
 * Unit tests for the in-memory {@link InMemorySystemMetrics} adapter (UC-46).
 * A {@link MutableClock} drives time so windowed counts are deterministic.
 */
class InMemorySystemMetricsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int THREADS = 8;
    private static final int RECORDS_PER_THREAD = 10_000;
    private static final long AWAIT_SECONDS = 30;

    // Requests record on many threads at once; the Javadoc promises none is lost.
    @Test
    void GivenConcurrentRecords_WhenTotalAndCount_ThenNoneIsLost() throws Exception {
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(new MutableClock(T0));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> recorders = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                recorders.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < RECORDS_PER_THREAD; i++) {
                        metrics.record(MetricType.VISITOR_ENTRY);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> recorder : recorders) {
                recorder.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long expected = (long) THREADS * RECORDS_PER_THREAD;
        assertEquals(expected, metrics.total(MetricType.VISITOR_ENTRY), "every record must reach the total");
        assertEquals(expected, metrics.count(MetricType.VISITOR_ENTRY, Duration.ofMinutes(1)),
                "every record must be in the window");
    }

    @Test
    void GivenEventsTenMinutesApart_WhenCountAndTotal_ThenOnlyCountsEventsInsideTheWindowAndTotalCountsAll() {
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
    void GivenOneTypeRecorded_WhenTotalAndCount_ThenTypesAreIndependentAndUnseenTypeIsZero() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);

        metrics.record(MetricType.RESERVATION);

        assertEquals(1, metrics.total(MetricType.RESERVATION));
        assertEquals(0, metrics.total(MetricType.REGISTRATION));
        assertEquals(0, metrics.count(MetricType.VISITOR_EXIT, Duration.ofHours(1)));
    }

    @Test
    void GivenALongGapBetweenEvents_WhenCountAndTotal_ThenWindowCountReflectsOnlyRecentEventsAndTotalStaysCumulative() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);

        metrics.record(MetricType.REGISTRATION);
        clock.plusSeconds(3 * 3600); // +3h (beyond retention) then a fresh event
        metrics.record(MetricType.REGISTRATION);

        assertEquals(1, metrics.count(MetricType.REGISTRATION, Duration.ofMinutes(5)));
        assertEquals(2, metrics.total(MetricType.REGISTRATION));
    }

    // #187: instants older than the two-hour retention are pruned, so a longer window would silently
    // count only its last two hours. It is refused instead.
    private static final Duration RETENTION = Duration.ofHours(2);

    @Test
    void GivenWindowOneMinuteLongerThanRetention_WhenCount_ThenThrowsIllegalArgument() {
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(new MutableClock(T0));
        Duration tooLong = RETENTION.plusMinutes(1);

        assertThrows(IllegalArgumentException.class, () -> metrics.count(MetricType.VISITOR_ENTRY, tooLong),
                "a " + tooLong + " window is longer than the " + RETENTION + " retention and must be refused");
    }

    @Test
    void GivenWindowEqualToRetention_WhenCount_ThenCountsEveryRetainedEvent() {
        MutableClock clock = new MutableClock(T0);
        InMemorySystemMetrics metrics = new InMemorySystemMetrics(clock);
        metrics.record(MetricType.VISITOR_ENTRY);
        clock.plusSeconds(RETENTION.toSeconds());
        metrics.record(MetricType.VISITOR_ENTRY);

        assertEquals(2, metrics.count(MetricType.VISITOR_ENTRY, RETENTION));
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
