package com.ticketing.system.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The sign-in lockout policy (SLR.2), bound from {@code auth.lockout.*} and validated at startup
 * (ADR-003).
 *
 * @param maxAttempts sign-in attempts allowed for one username inside one window; once they are used
 *                    up, further attempts are refused until the window ends. {@code 0} disables the
 *                    lockout
 * @param lockMinutes length of that window, which opens at the username's first failed attempt
 */
@Validated
@ConfigurationProperties(prefix = "auth.lockout")
public record LockoutProperties(@PositiveOrZero int maxAttempts, @Positive long lockMinutes) {
}
