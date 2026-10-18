package com.ticketing.system.shared.config;

import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The WSEP external-systems endpoint, bound from {@code wsep.*} and validated at startup
 * (ADR-003). A zero timeout or attempt count would otherwise fail every call, and the market would
 * never open.
 *
 * @param baseUrl               the single URL every WSEP action is POSTed to
 * @param requestTimeoutSeconds per-request timeout
 * @param handshakeAttempts     tries for the idempotent reachability handshake only
 * @param handshakeBackoffMs    base backoff between handshake tries
 */
@Validated
@ConfigurationProperties(prefix = "wsep")
public record WsepProperties(
        @NotBlank @URL String baseUrl,
        @Positive long requestTimeoutSeconds,
        @Positive int handshakeAttempts,
        @PositiveOrZero long handshakeBackoffMs) {
}
