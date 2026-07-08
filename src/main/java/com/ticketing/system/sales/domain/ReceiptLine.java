package com.ticketing.system.sales.domain;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * V3: an {@code @Embeddable} value object — one row in the owning OrderReceipt's
 * {@code receipt_lines} element collection (no identity of its own). Fields are non-final
 * with a protected no-arg ctor so Hibernate can hydrate; the public ctor still enforces
 * the invariants.
 */
@Embeddable
public class ReceiptLine implements InvariantChecked {

    @Column(name = "ticket_id", nullable = false)
    private int ticketId;

    @Column(nullable = false)
    private double price;

    @Column(name = "event_id", nullable = false)
    private int eventid;

    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    @Column(name = "zone_id", nullable = false)
    private int zoneId;

    @Column(name = "seat_number")
    private String seatNumber;

    /** For JPA only — do not call from application code. */
    protected ReceiptLine() { }

    public ReceiptLine(int ticketId, double price, int eventid, int zoneId, String seatNumber, LocalDateTime addedAt) {
        this.ticketId = ticketId;
        this.price = price;
        this.eventid = eventid;
        this.zoneId = zoneId;
        this.seatNumber = seatNumber;
        this.addedAt = addedAt;
        checkInvariants();
    }

    public boolean isExpired() {
        // return time at which the line was added + reservation hold duration < now. This is used to determine if the reservation hold has expired and the line can be removed from the receipt.
        return addedAt.plusMinutes(10).isBefore(LocalDateTime.now());   //TODO: use the timeout value from config.
    }

    public int getTicketId() {
        return ticketId;
    }

    public int getEventId() {
        return eventid;
    }

    public double getPriceAtReservation() {
        return price;
    }

    public int getZoneId() {
        return zoneId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    @Override
    public void checkInvariants() {
        if (ticketId <= 0) {
            throw new IllegalStateException("ReceiptLine invariant violated: ticketId must be positive (was " + ticketId + ")");
        }
        if (eventid <= 0) {
            throw new IllegalStateException("ReceiptLine invariant violated: eventid must be positive (was " + eventid + ")");
        }
        if (price < 0) {
            throw new IllegalStateException("ReceiptLine invariant violated: price must be >= 0 (was " + price + ")");
        }
        if (addedAt == null) {
            throw new IllegalStateException("ReceiptLine invariant violated: addedAt must not be null");
        }
        if (seatNumber != null && seatNumber.isBlank()) {
            throw new IllegalStateException("ReceiptLine invariant violated: seatNumber must not be blank if provided");
        }
        if (zoneId < 0) {
            throw new IllegalStateException("ReceiptLine invariant violated: zoneId must be non-negative (was " + zoneId + ")");
        }
    }
}
