package com.ticketing.system.shared.exception;

/**
 * The {@link EntityNotFoundException} for a ticket lookup: no such ticket exists. A ticket that
 * exists but whose status forbids the operation is {@link TicketNotAvailableException}.
 *
 * <p>No production code throws it today: {@code TicketRepository.findById} returns {@code null} for
 * an unknown id.
 */
public class TicketNotFoundException extends EntityNotFoundException {

    public TicketNotFoundException(Object ticketId) {
        super("Ticket", ticketId);
    }
}
