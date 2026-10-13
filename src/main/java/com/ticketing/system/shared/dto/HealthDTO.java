package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.Map;

// Health snapshot — distinct from Spring Actuator's /health.
// Returned by SystemAdminService for admin dashboards (SLR.8.1 telemetry anchor).
public record HealthDTO(
    String overallStatus,                // "UP" / "DEGRADED" / "DOWN"
    LocalDateTime checkedAt,
    Map<String, String> componentStatus  // e.g. {"paymentGateway": "UP", "ticketIssuer": "DOWN"}
) {}
