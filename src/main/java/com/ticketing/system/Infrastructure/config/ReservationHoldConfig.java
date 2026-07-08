package com.ticketing.system.Infrastructure.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${constants.ticket-reservation-duration}")
    private int reservationTimeoutMinutes;

    @PostConstruct
    void applyHoldWindow() {
        CartLineItem.setExpirationLimit(Duration.ofMinutes(reservationTimeoutMinutes));
    }
}
