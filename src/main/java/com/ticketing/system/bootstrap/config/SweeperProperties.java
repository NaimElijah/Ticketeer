package com.ticketing.system.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Session and cart sweeper settings (UC-2), bound from {@code sweeper.*} and validated at startup
 * (ADR-003).
 *
 * <p>Nothing injects this record: {@code SessionAndOrderSweeper} reads the same key through its
 * {@code @Scheduled} placeholder. Binding it only adds the bounds check, because a delay of zero or
 * less otherwise fails at context refresh with an error that does not name the property.
 *
 * @param fixedDelayMs milliseconds between the end of one sweep and the start of the next
 */
@Validated
@ConfigurationProperties(prefix = "sweeper")
public record SweeperProperties(@Positive long fixedDelayMs) {
}
