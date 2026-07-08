package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// Returned by SystemAdminService — gives admins visibility into platform state.
// UC-32 (after openMarket), and any state-introspection endpoint.
public record MarketStateDTO(
    String currentStatus,              // INITIALIZING / READY / OPEN / DEGRADED / CLOSED
    LocalDateTime lastInitializedAt,
    LocalDateTime lastOpenedAt,
    boolean paymentGatewayHealthy,
    boolean ticketIssuerHealthy,
    boolean defaultAdminPresent
) {}
