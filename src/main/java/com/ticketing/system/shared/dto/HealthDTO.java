package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A health snapshot of the platform's components, distinct from Spring Actuator's {@code /health}
 * (SLR.8.1). Nothing builds or returns it today: {@code SystemAdminService} reports the reachability of
 * the payment and ticket services through {@link MarketStateDTO}.
 *
 * @param overallStatus   {@code UP}, {@code DEGRADED} or {@code DOWN}
 * @param componentStatus the status of each component by name, such as {@code paymentGateway} to
 *                        {@code UP}
 */
public record HealthDTO(
    String overallStatus,
    LocalDateTime checkedAt,
    Map<String, String> componentStatus
) {}
