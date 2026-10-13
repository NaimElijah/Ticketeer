package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for Ticket lookups.
// Differs from TicketNotAvailableException: 'NotFound' = no such ticket exists;
// 'NotAvailable' = ticket exists but its status forbids the operation.
public class TicketNotFoundException extends EntityNotFoundException {

    public TicketNotFoundException(Object ticketId) {
        super("Ticket", ticketId);
    }
}
