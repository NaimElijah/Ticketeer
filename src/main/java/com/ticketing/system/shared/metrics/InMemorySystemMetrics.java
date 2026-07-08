package com.ticketing.system.shared.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory {@link ISystemMetrics} adapter (V1/V2). Each {@link MetricType}
 * keeps a time-ordered deque of event instants (for windowed rate / throughput
 * queries) plus a cumulative counter. Instants older than {@link #RETENTION}
 * are pruned from the head on write so the deques stay bounded.
 *
 * <p>Thread-safe: the per-type deques are {@link ConcurrentLinkedDeque} and the
 * totals are {@link AtomicLong}. Counters reset on restart — acceptable for the
 * in-memory runtime; the port boundary keeps a durable adapter a drop-in for V3.
 */
@Component
@Slf4j
public class InMemorySystemMetrics implements ISystemMetrics {

    /** The longest window any caller asks for is one hour; keep slack and prune the rest. */
    private static final Duration RETENTION = Duration.ofHours(2);

    private final Clock clock;
    private final Map<MetricType, ConcurrentLinkedDeque<Instant>> events = new EnumMap<>(MetricType.class);
    private final Map<MetricType, AtomicLong> totals = new EnumMap<>(MetricType.class);

    public InMemorySystemMetrics(Clock clock) {
        this.clock = clock;
        for (MetricType type : MetricType.values()) {
            events.put(type, new ConcurrentLinkedDeque<>());
            totals.put(type, new AtomicLong());
        }
    }

    @Override
    public void record(MetricType type) {
        Instant now = clock.instant();
        events.get(type).addLast(now);
        totals.get(type).incrementAndGet();
        prune(type, now);
    }

    @Override
    public long count(MetricType type, Duration within) {
        Instant cutoff = clock.instant().minus(within);
        // The deque is append-ordered by time, so walk from the newest end and
        // stop at the first instant older than the cutoff — O(window), not O(retention).
        long n = 0;
        Iterator<Instant> it = events.get(type).descendingIterator();
        while (it.hasNext()) {
            if (it.next().isBefore(cutoff)) {
                break;
            }
            n++;
        }
        return n;
    }

    @Override
    public long total(MetricType type) {
        return totals.get(type).get();
    }

    /** Drops instants older than the retention window from the head of the deque. */
    private void prune(MetricType type, Instant now) {
        Instant cutoff = now.minus(RETENTION);
        ConcurrentLinkedDeque<Instant> deque = events.get(type);
        Instant head;
        while ((head = deque.peekFirst()) != null && head.isBefore(cutoff)) {
            deque.pollFirst();
        }
    }
}
