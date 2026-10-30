package com.ticketing.system.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Session lifetimes, bound from {@code session.*} and validated at startup (ADR-003).
 *
 * @param memberTtlMinutes        absolute lifetime of a member session and of its JWT
 * @param guestIdleTimeoutMinutes lifetime of a guest session, counted from its start; nothing
 *                                extends it yet (OQ-8)
 */
@Validated
@ConfigurationProperties(prefix = "session")
public record SessionProperties(@Positive long memberTtlMinutes, @Positive long guestIdleTimeoutMinutes) {
}
