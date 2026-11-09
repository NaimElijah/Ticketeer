package com.ticketing.system.shared.exception;

/**
 * Thrown by {@code Ticket.reserve} when the ticket's status is not {@code AVAILABLE}, which also
 * leaves the ticket as it was. SLR.1.2 race condition prevention (UC-9).
 *
 * <p>No production path reaches it today: {@code Ticket.reserve} is called only from tests.
 */
public class TicketNotAvailableException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param ticketId the ticket that is not available
     */
    public TicketNotAvailableException(Object ticketId) {
        super("Ticket not available: " + ticketId);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the reason the ticket cannot be reserved
     */
    public TicketNotAvailableException(String message) {
        super(message);
    }
}
