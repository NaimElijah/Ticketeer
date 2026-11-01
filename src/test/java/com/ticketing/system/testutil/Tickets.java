package com.ticketing.system.testutil;

import java.lang.reflect.Field;

import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;

/** Ticket fixtures for states no public transition reaches. */
public final class Tickets {

    private Tickets() { }

    /**
     * A ticket as loaded from a stored row in {@code status}. A new {@link Ticket} starts PAID, and no
     * public transition reaches AVAILABLE or RESERVED from there: only stored rows hold those states.
     * The services' {@code else -> markVoided()} branches handle exactly those rows, so a test of those
     * branches needs such a ticket. Like a JPA load, this sets the status field by reflection, bypassing
     * the transitions {@link TicketStatus} allows.
     *
     * @param status         the stored status
     * @param eventId        the event, positive
     * @param zoneId         the zone, zero or more
     * @param orderReceiptId the receipt, positive
     * @param seatLabel      the seat, or {@code null} for a standing ticket
     * @param price          the price, zero or more
     * @param ticketId       the ticket id, positive
     * @param barcode        the ticket code
     * @return the ticket, in {@code status}
     */
    public static Ticket storedAs(TicketStatus status, int eventId, int zoneId, int orderReceiptId,
            String seatLabel, double price, int ticketId, String barcode) {
        Ticket ticket = new Ticket(eventId, zoneId, orderReceiptId, seatLabel, price, ticketId, barcode);
        try {
            Field statusField = Ticket.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(ticket, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Ticket's status field could not be set as a JPA load would", e);
        }
        return ticket;
    }
}
