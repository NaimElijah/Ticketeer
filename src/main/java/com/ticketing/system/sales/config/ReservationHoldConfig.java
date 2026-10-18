package com.ticketing.system.sales.config;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;

import com.ticketing.system.sales.domain.CartLineItem;

import jakarta.annotation.PostConstruct;

/**
 * Applies the configurable cart-reservation hold window
 * ({@code constants.ticket-reservation-duration}, in minutes) to the domain once at startup, so
 * {@link CartLineItem}'s expiry check uses the configured value instead of a hardcoded constant.
 *
 * <p>The window is a system-wide constant held statically on {@code CartLineItem} (it is not
 * per-item state, so it is never persisted), so it is set here rather than injected into every
 * value object. This keeps the domain free of Spring config while still being configuration-driven.
 */
@Configuration
public class ReservationHoldConfig {

    private final ReservationHoldProperties properties;

    public ReservationHoldConfig(ReservationHoldProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void applyHoldWindow() {
        CartLineItem.setExpirationLimit(Duration.ofMinutes(properties.ticketReservationDuration()));
    }
}
