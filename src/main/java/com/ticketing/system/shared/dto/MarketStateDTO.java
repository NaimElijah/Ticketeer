package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// Returned by SystemAdminService — gives admins visibility into platform state.
// UC-32 (after openMarket), and any state-introspection endpoint.
public record MarketStateDTO(
    String currentStatus,              // UNINITIALIZED / READY / OPEN / CLOSED
    LocalDateTime lastInitializedAt,
    LocalDateTime lastOpenedAt,
    boolean paymentGatewayHealthy,
    boolean ticketIssuerHealthy,
    boolean defaultAdminPresent
) {}
