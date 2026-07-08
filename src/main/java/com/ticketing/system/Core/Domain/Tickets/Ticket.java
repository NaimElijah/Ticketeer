package com.ticketing.system.Core.Domain.Tickets;
import com.ticketing.system.identity.domain.Admin;


import com.ticketing.system.shared.exception.TicketNotAvailableException;
import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Unified Ticket aggregate root (inventory + post-purchase record).
 *
 * <p>V3: mapped to JPA as a flat row — the four references ({@code eventId},
 * {@code zoneid}, {@code orderReceiptId}, {@code holderUserId}) are plain by-id
 * columns, never {@code @ManyToOne} (the cross-aggregate reference rule). The
 * {@code ticketId} {@code @Id} is ASSIGNED by the issuer (the barcode/ticketId the
 * ticket issuer returns), never {@code @GeneratedValue}. {@code status} is stored by
 * name ({@code @Enumerated(STRING)}) so the column is stable against enum reordering,
 * and {@code @Version} drives optimistic locking. A protected no-arg constructor lets
 * Hibernate hydrate instances; the public constructor still enforces the invariants.
 */
@Entity
@Table(name = "tickets")
public class Ticket implements InvariantChecked {

    @Column(name = "zone_id", nullable = false)
    private int zoneid;

    @Column(name = "event_id", nullable = false)
    private int eventId;

    @Column(name = "seat_number")
    private String seatNumber;   // nullable for standing zones, non-null for seated zones. Represents the seat label this ticket refers to inside its zone.

    @Column(nullable = false)
    private double price;

    @Id
    private int ticketId;

    @Column(name = "order_receipt_id", nullable = false)
    private int orderReceiptId;

    @Column(name = "barcode_value")
    private String barcodeValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    // D7 (auth rework #181): nullable. Set on the Member path of CheckoutService;
    // stays null for Guest purchases. Lets UC-16 view-my-history go through
    // ITicketRepository.findByHolderUserId(int) in a single hop.
    @Column(name = "holder_user_id")
    private Integer holderUserId;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Ticket() { }


    /**
     * Seated-zone ticket constructor — {@code seatLabel} is the label of the
     * {@link com.ticketing.system.Core.Domain.events.Seat} this ticket refers
     * to inside its {@link com.ticketing.system.Core.Domain.events.SeatedZone}.
     * Pass {@code null} for standing-zone tickets (or use the 5-arg form).
     */
    public Ticket(int eventId, int zoneid, int orderReceiptId, String seatLabel, double price, int ticketId, String barcodeValue) {
        this.eventId = eventId;
        this.zoneid = zoneid;
        this.seatNumber = seatLabel;    // nullable for standing zones, non-null for seated zones
        this.price = price;
        this.ticketId = ticketId;
        this.orderReceiptId = orderReceiptId;
        this.barcodeValue = barcodeValue;
        this.status = TicketStatus.PAID; // Default initial status, because tickets are created at payment time in the current design. Adjust as needed if creation timing changes.
        this.holderUserId = null;
        checkInvariants();
    }



    public int getId() {
        return ticketId;
    }

    public int getEventId() {
        return eventId;
    }

    public double getPrice() {
        return price;
    }

    public int getOrderReceiptId() {
        return orderReceiptId;
    }

    public void setOrderReceiptId(int orderReceiptId) {
        if (orderReceiptId <= 0) {
            throw new IllegalArgumentException("orderReceiptId must be positive");
        }
        this.orderReceiptId = orderReceiptId;
        checkInvariants();
    }

    // ---------------------------------------------------------------------------
    // Skeleton additions for the unified-Ticket aggregate.
    // State machine: AVAILABLE -> RESERVED -> PAID -> ISSUED -> USED | REFUNDED | VOIDED
    // ---------------------------------------------------------------------------

    // UC-9 / UC-5 — AVAILABLE -> RESERVED. Throws TicketNotAvailableException if not AVAILABLE.
    public void reserve(int holderUserId) {
        if (!isAvailable()) {
            throw new TicketNotAvailableException(
                    "Ticket " + ticketId + " is not available for reservation (current status: " + status + ")");
        }
        if (holderUserId <= 0) {
            throw new IllegalArgumentException("holderUserId must be positive");
        }
        this.holderUserId = holderUserId;
        this.status = TicketStatus.RESERVED;
        checkInvariants();
    }

    // UC-10 — RESERVED -> PAID after successful charge.
    public void markPaid() {
        this.status = TicketStatus.PAID;
        checkInvariants();
    }

    // UC-10 / UC-34 — PAID -> ISSUED after successful external issuance, stores barcode locally.
    public void markIssued(String barcodeValue) {
        this.barcodeValue = barcodeValue;
        this.status = TicketStatus.ISSUED;
        checkInvariants();
    }

    // ISSUED -> USED at venue gate scan (no UC in v0; defined for completeness).
    public void markUsed() {
        this.status = TicketStatus.USED;
        checkInvariants();
    }

    // UC-4 — PAID/ISSUED -> REFUNDED via auto-refund pipeline.
    public void markRefunded() {
        this.status = TicketStatus.REFUNDED;
        checkInvariants();
    }

    // Admin / ops action — any state -> VOIDED.
    public void markVoided() {
        this.status = TicketStatus.VOIDED;
        checkInvariants();
    }

    // UC-2 — RESERVED -> AVAILABLE on cart expiration. Releases the lock.
    public void release() {
        this.status = TicketStatus.AVAILABLE;
        checkInvariants();
    }

    // State checks.
    public boolean isAvailable() {
        return status == TicketStatus.AVAILABLE;
    }

    public boolean isReserved() {
        return status == TicketStatus.RESERVED;
    }

    public boolean isPaid() {
        return status == TicketStatus.PAID;
    }

    public boolean isIssued() {
        return status == TicketStatus.ISSUED;
    }

    public boolean isSeatedTicket() {
        return seatNumber != null;
    }

    public boolean isStandingTicket() {
        return seatNumber == null;
    }

    // Missing getters per the unified-Ticket model.
    public int getZoneId() {
        return zoneid;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public Integer getHolderUserId() {
        return holderUserId;
    }

    /**
     * Assigns this ticket to a Member at checkout (D7). Called once from the
     * Member path of {@code CheckoutService.checkout}. Guest tickets stay
     * unassigned (holderUserId remains null).
     */
    public void setHolderUserId(Integer userId) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("holderUserId must be positive when set");
        }
        this.holderUserId = userId;
        checkInvariants();
    }

    public String getBarcode() {
        return barcodeValue;
    }

    public void setBarcodeValue(String barcodeValue) {
       this.barcodeValue=barcodeValue;
       checkInvariants();
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setTicketId(int ticketId) {
        // Validate-before-commit: roll back if the new id is non-positive, so a rejected
        // call never leaves the ticket with a corrupted id.
        int previous = this.ticketId;
        this.ticketId = ticketId;
        try {
            checkInvariants();
        } catch (RuntimeException ex) {
            this.ticketId = previous;
            throw ex;
        }
    }
    

    
    

    @Override
    public void checkInvariants() {
        if (ticketId <= 0) {
            throw new IllegalStateException(
                    "Ticket invariant violated: ticketId must be positive (was " + ticketId + ")");
        }
        if (eventId <= 0) {
            throw new IllegalStateException(
                    "Ticket invariant violated: eventId must be positive (was " + eventId + ")");
        }
        if (zoneid < 0) {
            throw new IllegalStateException("Ticket invariant violated: zoneId must be >= 0 (was " + zoneid + ")");
        }
        if (price < 0) {
            throw new IllegalStateException("Ticket invariant violated: price must be >= 0 (was " + price + ")");
        }
        if (status == null) {
            throw new IllegalStateException("Ticket invariant violated: status must not be null");
        }
        if (holderUserId != null && holderUserId <= 0) {
            throw new IllegalStateException(
                    "Ticket invariant violated: holderUserId must be positive when set (was " + holderUserId + ")");
        }
        if (orderReceiptId <= 0) {
            throw new IllegalStateException("Ticket invariant violated: orderReceiptId must be positive (was " + orderReceiptId + ")");
        }
        // seatNumber (aka seatLabel) is nullable: standing-zone tickets have null,
        // seated-zone tickets carry the seat label they reference. If set, must be non-blank.
        if (seatNumber != null && seatNumber.isBlank()) {
            throw new IllegalStateException("Ticket invariant violated: seatLabel must be non-blank when set");
        }
    }
    

}