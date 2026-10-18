package com.ticketing.system.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The sign-in lockout policy (SLR.2), bound from {@code auth.lockout.*} and validated at startup
 * (ADR-003).
 *
 * @param maxAttempts failed sign-ins before the account locks; {@code 0} disables the lockout
 * @param lockMinutes how long a locked account stays locked
 */
@Validated
@ConfigurationProperties(prefix = "auth.lockout")
public record LockoutProperties(@PositiveOrZero int maxAttempts, @Positive long lockMinutes) {
}
