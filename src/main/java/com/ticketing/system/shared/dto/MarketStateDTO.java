package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * The platform's market state, as {@code SystemAdminService} reports it to the admin dashboard (UC-32).
 *
 * <p>The two reachability flags are the result of the last WSEP probe, taken at
 * {@code lastProbedAt}; nothing probes when this record is built. A {@code null}
 * {@code lastProbedAt} means no probe has run yet, so both flags are {@code false} and mean
 * "not checked yet", not "down".
 *
 * @param currentStatus       the lifecycle state: {@code UNINITIALIZED}, {@code READY}, {@code OPEN} or {@code CLOSED}
 * @param lastInitializedAt   when the platform last became READY, in UTC wall time (ADR-008); {@code null} before
 * @param lastOpenedAt        when the market last opened, in UTC wall time; {@code null} before
 * @param paymentGatewayHealthy whether a payment service answered the last probe
 * @param ticketIssuerHealthy whether a ticket issuer answered the last probe
 * @param lastProbedAt        when the last probe finished, in UTC wall time; {@code null} if none has run
 * @param defaultAdminPresent whether at least one System Admin exists
 */
public record MarketStateDTO(
    String currentStatus,
    LocalDateTime lastInitializedAt,
    LocalDateTime lastOpenedAt,
    boolean paymentGatewayHealthy,
    boolean ticketIssuerHealthy,
    LocalDateTime lastProbedAt,
    boolean defaultAdminPresent
) {}
