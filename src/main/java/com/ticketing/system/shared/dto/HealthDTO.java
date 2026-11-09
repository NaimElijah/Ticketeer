package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.Map;

// Health snapshot — distinct from Spring Actuator's /health (SLR.8.1). Nothing builds or returns it
// today: SystemAdminService reports the reachability of the payment and ticket services through
// MarketStateDTO.
public record HealthDTO(
    String overallStatus,                // "UP" / "DEGRADED" / "DOWN"
    LocalDateTime checkedAt,
    Map<String, String> componentStatus  // e.g. {"paymentGateway": "UP", "ticketIssuer": "DOWN"}
) {}
