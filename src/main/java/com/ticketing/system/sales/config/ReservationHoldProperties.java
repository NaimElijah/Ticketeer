package com.ticketing.system.sales.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * The ticket reservation hold, bound from {@code constants.*} and validated at startup, so an
 * out-of-bounds value stops the boot instead of being replaced (ADR-003).
 *
 * @param ticketReservationDuration minutes a reserved ticket stays held in an active order
 */
@Validated
@ConfigurationProperties(prefix = "constants")
public record ReservationHoldProperties(@Positive int ticketReservationDuration) {
}
