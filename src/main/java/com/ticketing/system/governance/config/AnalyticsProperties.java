package com.ticketing.system.governance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.ticketing.system.shared.metrics.InMemorySystemMetrics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * System Analytics dashboard settings (UC-46), bound from {@code analytics.*} and validated at
 * startup (ADR-003).
 *
 * @param rateWindowMinutes trailing window, in minutes, that the per-minute rates are computed over;
 *                          at most the metrics retention, {@link InMemorySystemMetrics#RETENTION_MINUTES}
 * @param refreshIntervalMs milliseconds between the dashboard's automatic refreshes; {@code 0}
 *                          disables the auto-refresh, leaving only the manual one
 */
@Validated
@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(
        @Positive @Max(InMemorySystemMetrics.RETENTION_MINUTES) int rateWindowMinutes,
        @PositiveOrZero int refreshIntervalMs) {
}
