package com.ticketing.system.shared.exception;
import com.ticketing.system.sales.application.service.ReservationService;

// Thrown when a Ticket cannot be reserved because its current state is not AVAILABLE.
// SLR.1.2 race condition prevention — used by ReservationService (UC-9).
public class TicketNotAvailableException extends DomainException {

    public TicketNotAvailableException(Object ticketId) {
        super("Ticket not available: " + ticketId);
    }

    public TicketNotAvailableException(String message) {
        super(message);
    }
}
