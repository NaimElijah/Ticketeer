package com.ticketing.system.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Notification polling settings, bound from {@code notifications.*} and validated at startup
 * (ADR-003).
 *
 * <p>Nothing injects this record: {@code NotificationPollingScheduler} reads the same key through
 * its {@code @Scheduled} placeholder. Binding it only adds the bounds check, because a delay of zero
 * or less otherwise fails at context refresh with an error that does not name the property.
 *
 * @param pollDelayMs milliseconds between the end of one poll of the connected users and the start
 *                    of the next
 */
@Validated
@ConfigurationProperties(prefix = "notifications")
public record NotificationPollingProperties(@Positive long pollDelayMs) {
}
