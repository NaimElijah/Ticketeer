package com.ticketing.system.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Market self-heal settings, bound from {@code market.*} and validated at startup (ADR-003).
 *
 * <p>Nothing injects this record: {@code MarketSelfHealScheduler} reads the same key through its
 * {@code @Scheduled} placeholder. Binding it only adds the bounds check, because a delay of zero or
 * less otherwise fails at context refresh with an error that does not name the property.
 *
 * @param selfHealDelayMs milliseconds between attempts to open a market an outage left closed
 */
@Validated
@ConfigurationProperties(prefix = "market")
public record MarketProperties(@Positive long selfHealDelayMs) {
}
