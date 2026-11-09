package com.ticketing.system.shared.exception;

/**
 * Thrown when the external ticket-issuance service fails to issue the tickets after the charge: it
 * rejects the request, or its reply fails validation. UC-34.
 *
 * <p>This exception triggers the UC-4 auto-refund flow (I.3.3). Checkout reports the failure to the
 * buyer without the message, because it can carry the issuer's own error text.
 */
public class TicketIssuanceFailedException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason what failed; the message prefixes it with "Ticket issuance failed: "
     */
    public TicketIssuanceFailedException(String reason) {
        super("Ticket issuance failed: " + reason);
    }

    /**
     * Creates the exception with the failure behind it.
     *
     * @param reason what failed; the message prefixes it with "Ticket issuance failed: "
     * @param cause the underlying failure, or {@code null}
     */
    public TicketIssuanceFailedException(String reason, Throwable cause) {
        super("Ticket issuance failed: " + reason, cause);
    }
}
