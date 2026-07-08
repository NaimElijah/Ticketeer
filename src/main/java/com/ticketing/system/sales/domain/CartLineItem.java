package com.ticketing.system.sales.domain;

import com.ticketing.system.Core.Application.dto.ActiveOrderDTO.CartLineDTO;
import com.ticketing.system.shared.InvariantChecked;

import java.time.LocalDateTime;
import java.time.Duration;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * V3: an {@code @Embeddable} value object — one row in the owning ActiveOrder's
 * {@code active_order_items} element collection (no identity of its own). event/zone are by-id
 * values. Fields are non-final with a protected no-arg ctor so Hibernate can hydrate; the public
 * ctor still enforces the invariants.
 *
 * <p>The reservation hold window is a system-wide configurable constant
 * ({@code constants.ticket-reservation-duration}), applied once at startup by
 * {@code ReservationHoldConfig}. It is {@code static} (not per-item state, so never persisted) and
 * shared by every line item; it defaults to 10 minutes until the config is applied (e.g. in plain
 * unit tests that don't boot Spring).
 */
@Embeddable
public class CartLineItem implements InvariantChecked {

    @Column(name = "event_id", nullable = false)
    private int eventId;

    @Column(name = "zone_id", nullable = false)
    private int zoneId;

    @Column(name = "price", nullable = false)
    private double priceAtoneticketReservation;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "seat_number")
    private String seatNumber;  // null for standing zones

    private static volatile Duration expirationLimit = Duration.ofMinutes(10);

    /** Applied once at startup from {@code constants.ticket-reservation-duration} (see ReservationHoldConfig). */
    public static void setExpirationLimit(Duration limit) {
        if (limit != null && !limit.isZero() && !limit.isNegative()) {
            expirationLimit = limit;
        }
    }

    /** The configured reservation hold window (the same value the expiry sweep uses). */
    public static Duration getExpirationLimit() {
        return expirationLimit;
    }

    /** For JPA only — do not call from application code. */
    protected CartLineItem() { }

    public CartLineItem(int eventId, int zoneId, String seatNumber, double priceAtReservation, LocalDateTime addedAt) {
        this.eventId = eventId;
        this.zoneId = zoneId;
        this.seatNumber = seatNumber;
        this.priceAtoneticketReservation = priceAtReservation;
        this.addedAt = addedAt;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (eventId <= 0) {
            throw new IllegalStateException("CartLineItem invariant violated: eventId must be positive (was " + eventId + ")");
        }
        if (zoneId < 0) {
            throw new IllegalStateException("CartLineItem invariant violated: zoneId must be non-negative (was " + zoneId + ")");
        }
        if (priceAtoneticketReservation < 0) {
            throw new IllegalStateException("CartLineItem invariant violated: price must be >= 0 (was " + priceAtoneticketReservation + ")");
        }
        if (addedAt == null) {
            throw new IllegalStateException("CartLineItem invariant violated: addedAt must not be null");
        }
        if (seatNumber != null && seatNumber.isBlank()) {
            throw new IllegalStateException("CartLineItem invariant violated: seatNumber must not be blank if provided");
        }
    }

    // Getters
    public int geteventId() {
        return eventId;
    }

    public int getzoneId() {
        return zoneId;
    }

    public double getPriceAtReservation() {
        return priceAtoneticketReservation;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    /** Resets the reservation hold timer to a fresh window starting at {@code newAddedAt}. */
    public void renew(LocalDateTime newAddedAt) {
        this.addedAt = newAddedAt;
        checkInvariants();   // re-runs the existing invariant guard (addedAt != null, etc.)
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public boolean isSeatedTicket() {
        return seatNumber != null;
    }

    public boolean isStandingTicket() {
        return seatNumber == null;
    }

    public Duration getRemainingTime() {

        if (addedAt == null) {
            return Duration.ZERO;
        }

        Duration elapsedTime = Duration.between(addedAt, LocalDateTime.now());

        Duration remaining = expirationLimit.minus(elapsedTime);

        if (remaining.isNegative() || remaining.isZero()) {

            return Duration.ZERO;
        } else {

            return remaining;
        }
    }

    public boolean isExpired() {
        return getRemainingTime().isZero();
    }

    public CartLineDTO toDTO() {
        return new CartLineDTO(
                eventId,
                "Event Name Placeholder", // This should be replaced with actual event name retrieval logic
                zoneId,
                seatNumber,   // seatNumber is null for standing-zone tickets
                priceAtoneticketReservation,
                addedAt);
    }

}