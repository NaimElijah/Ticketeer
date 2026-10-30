package com.ticketing.system.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Event-completion sweeper settings, bound from {@code event-completion.*} and validated at startup
 * (ADR-003).
 *
 * <p>Nothing injects this record: {@code EventCompletionSweeper} reads the same key through its
 * {@code @Scheduled} placeholder. Binding it only adds the bounds check, because a delay of zero or
 * less otherwise fails at context refresh with an error that does not name the property.
 *
 * @param fixedDelayMs milliseconds between the end of one completion sweep and the start of the next
 */
@Validated
@ConfigurationProperties(prefix = "event-completion")
public record EventCompletionProperties(@Positive long fixedDelayMs) {
}
