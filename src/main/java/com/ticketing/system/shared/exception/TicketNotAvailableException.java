package com.ticketing.system.shared.exception;

/**
 * Thrown by {@code Ticket.reserve} when the ticket's status is not {@code AVAILABLE}, which also
 * leaves the ticket as it was. SLR.1.2 race condition prevention (UC-9). The message reaches the
 * user.
 */
public class TicketNotAvailableException extends DomainException {

    public TicketNotAvailableException(Object ticketId) {
        super("Ticket not available: " + ticketId);
    }

    public TicketNotAvailableException(String message) {
        super(message);
    }
}
