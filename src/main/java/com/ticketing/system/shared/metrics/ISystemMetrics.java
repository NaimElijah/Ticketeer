package com.ticketing.system.shared.metrics;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;

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
 * <p>The adapter lives in Infrastructure (V1/V2: in-memory; swappable for a
 * durable / Micrometer-backed store in V3 without touching callers — ports &
 * adapters).
 */
public interface ISystemMetrics {

    /** Records one occurrence of {@code type} at the current instant. */
    void record(MetricType type);

    /** Number of {@code type} events recorded within the trailing {@code within} window. */
    long count(MetricType type, Duration within);

    /** Cumulative number of {@code type} events recorded since startup. */
    long total(MetricType type);
}
