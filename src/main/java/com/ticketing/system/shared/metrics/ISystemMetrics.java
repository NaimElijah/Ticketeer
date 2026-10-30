package com.ticketing.system.shared.metrics;

import java.time.Duration;

/**
 * Outbound port for recording and reading the lightweight platform-activity
 * counters that feed the System Analytics dashboard (UC-46 / #43, #279).
 *
 * <p>Write side: the auth / reservation slices (and the session sweeper) call
 * {@link #record(MetricType)} as events occur. Read side:
 * {@code SystemAnalyticsService} calls {@link #count(MetricType, Duration)} and
 * {@link #total(MetricType)} to compute live rates and throughput.
 *
 * <p>The only adapter is {@link InMemorySystemMetrics}, in this package; its counters reset on
 * restart. A durable adapter could replace it without touching callers.
 */
public interface ISystemMetrics {

    /**
     * The longest window, in minutes, that {@link #count} accepts from any adapter. An adapter keeps
     * events at least this long; {@code AnalyticsProperties} caps its rate window at this value.
     */
    int MAX_WINDOW_MINUTES = 120;

    /** Records one occurrence of {@code type} at the current instant. */
    void record(MetricType type);

    /**
     * Number of {@code type} events recorded within the trailing {@code within} window.
     *
     * @throws IllegalArgumentException if {@code within} is longer than {@link #MAX_WINDOW_MINUTES}
     */
    long count(MetricType type, Duration within);

    /** Cumulative number of {@code type} events recorded since startup. */
    long total(MetricType type);
}
