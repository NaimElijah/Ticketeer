package com.ticketing.system.shared.exception;

// Thrown when the external ticket-issuance service fails after a successful charge.
// This exception is the trigger for UC-4 auto-refund flow (I.3.3).
// UC-34.
public class TicketIssuanceFailedException extends DomainException {

    public TicketIssuanceFailedException(String reason) {
        super("Ticket issuance failed: " + reason);
    }

    public TicketIssuanceFailedException(String reason, Throwable cause) {
        super("Ticket issuance failed: " + reason, cause);
    }
}
