package com.ticketing.system.sales.domain;

/**
 * Lifecycle states of a {@link Ticket} (unified inventory + post-purchase aggregate).
 *
 * <p>The legal transitions, which {@link Ticket} enforces through {@link #canBecome}:
 * <ul>
 *   <li>AVAILABLE → RESERVED (UC-9 reservation, {@link Ticket#reserve})</li>
 *   <li>RESERVED → PAID (UC-10 successful charge, {@link Ticket#markPaid})</li>
 *   <li>PAID → ISSUED (UC-34 successful issuance, {@link Ticket#markIssued})</li>
 *   <li>ISSUED → USED (gate scan, no UC yet, {@link Ticket#markUsed})</li>
 *   <li>PAID or ISSUED → REFUNDED (UC-4 refund, {@link Ticket#markRefunded})</li>
 *   <li>RESERVED → AVAILABLE (UC-2 expiry release, {@link Ticket#release})</li>
 *   <li>any state → VOIDED ({@link Ticket#markVoided}). An event cancellation voids the tickets never
 *       sold, and the USED ones of a refunded receipt; a member refund voids every ticket of the order that
 *       is not PAID or ISSUED. Whether a USED or REFUNDED ticket should be voidable is an open question
 *       (requirements.md, OQ-30).</li>
 * </ul>
 * Every other change is refused: reserving a ticket that is not AVAILABLE with
 * {@code TicketNotAvailableException}, and any other illegal change with
 * {@code InvalidStateTransitionException}; either leaves the ticket as it was. TicketStatusTest pins the whole
 * table. A ticket is created PAID, at checkout, since the current design
 * creates tickets only once they are paid for; AVAILABLE and RESERVED are reached only by a row
 * stored with them.
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PAID,
    ISSUED,
    USED,
    REFUNDED,
    VOIDED;

    /**
     * Whether a ticket in this state may move to {@code next}, per the transitions listed on the type.
     *
     * @param next the state asked for
     * @return {@code true} if the transition is legal
     */
    public boolean canBecome(TicketStatus next) {
        return switch (next) {
            case AVAILABLE -> this == RESERVED;
            case RESERVED -> this == AVAILABLE;
            case PAID -> this == RESERVED;
            case ISSUED -> this == PAID;
            case USED -> this == ISSUED;
            case REFUNDED -> this == PAID || this == ISSUED;
            case VOIDED -> true;
        };
    }
}
