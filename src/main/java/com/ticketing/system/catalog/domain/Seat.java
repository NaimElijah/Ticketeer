package com.ticketing.system.catalog.domain;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A single addressable seat inside a {@link SeatedZone}.
 *
 * <p>Carries its own lifecycle status and 2D layout coordinates (so the
 * UI can render an arbitrary-shaped venue without forcing a grid). The
 * label is the stable identity — coordinates can be tweaked for layout
 * without invalidating tickets that reference this seat by label.
 *
 * <p>When status is {@code RESERVED}, {@link #reservedByOrderKey} records
 * which {@code ActiveOrder} holds the reservation, enabling the 3-phase
 * checkout to verify ownership before confirming sale — even after the
 * event lock is released during the payment/issuance phase.
 *
 * <p>V3: an owned child {@code @Entity} of a SeatedZone (mapped via its {@code @OneToMany} seat map,
 * keyed by {@code label}). The {@code @Id} is a DB-generated surrogate because a seat label is only
 * unique within its zone, not globally; {@code label} stays a column (and the map key). Status is
 * stored by name; {@code reservedByOrderKey} is a nullable by-id column. {@code @Version} gives each
 * seat its own optimistic lock so independent seat reservations never false-conflict. Fields are
 * non-final with a protected no-arg ctor for Hibernate; the public ctor enforces the invariants.
 */
@Entity
@Table(name = "seats")
public class Seat implements InvariantChecked {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seatPk;

    @Column(nullable = false)
    private String label;

    @Column(name = "x_coord", nullable = false)
    private double x;

    @Column(name = "y_coord", nullable = false)
    private double y;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    /** Non-null only while status == RESERVED; identifies the holding ActiveOrder. */
    @Column(name = "reserved_by_order_key")
    private String reservedByOrderKey;
    /** When the hold expires (informational — enforcement is in the sweep job). */
    @Column(name = "reserved_until")
    private LocalDateTime reservedUntil; //TODO: when going to checkout, this should be set to {time now} + {reservation timeout period}.

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Seat() { }

    public Seat(String label, double x, double y) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.status = SeatStatus.AVAILABLE;
        checkInvariants();
    }

    public String getLabel() {
        return label;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public SeatStatus getStatus() {
        return status;
    }

    /** Returns the order key that currently holds this seat, or {@code null} if not reserved. */
    public String getReservedByOrderKey() {
        return reservedByOrderKey;
    }

    public LocalDateTime getReservedUntil() {
        return reservedUntil;
    }

    /** Package-private — only {@link SeatedZone} should drive seat transitions. */
    void setStatus(SeatStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Seat status must not be null");
        }
        this.status = status;
    }

    /**
     * Package-private — called by {@link SeatedZone} when reserving a seat.
     * Records which order holds the seat and when the hold expires.
     */
    void markReservedBy(String orderKey, LocalDateTime reservedUntil) {
        if (orderKey == null || orderKey.isBlank()) {
            throw new IllegalArgumentException("orderKey must be non-blank");
        }
        this.reservedByOrderKey = orderKey;
        this.reservedUntil = reservedUntil;
        this.status = SeatStatus.RESERVED;
        checkInvariants();
    }

    /**
     * Package-private — called by {@link SeatedZone} on release or sale confirmation.
     * Clears reservation ownership.
     */
    void clearReservation() {
        this.reservedByOrderKey = null;
        this.reservedUntil = null;
    }

    @Override
    public void checkInvariants() {
        if (label == null || label.isBlank()) {
            throw new IllegalStateException("Seat invariant violated: label must be non-blank");
        }
        if (status == null) {
            throw new IllegalStateException("Seat invariant violated: status must not be null");
        }
        if (status == SeatStatus.RESERVED && (reservedByOrderKey == null || reservedByOrderKey.isBlank())) {
            throw new IllegalStateException(
                    "Seat invariant violated: RESERVED seat must have a non-blank reservedByOrderKey");
        }
        if (status != SeatStatus.RESERVED && reservedByOrderKey != null) {
            throw new IllegalStateException(
                    "Seat invariant violated: non-RESERVED seat must not have a reservedByOrderKey");
        }
    }
}
