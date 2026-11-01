package com.ticketing.system.shared.exception;

/**
 * The ticket issuer could not be reached (connection refused, timeout, no answer), so issuance never got a
 * verdict. An outage that needs an operator, unlike a refusal by the issuer, which stays a plain
 * {@link TicketIssuanceFailedException}. It extends that type, so every existing catch and the
 * presenter's reason-free outcome still apply. Mirrors {@link PaymentGatewayUnreachableException}.
 */
public class TicketIssuerUnreachableException extends TicketIssuanceFailedException {

    /**
     * @param reason what failed
     * @param cause  the transport failure
     */
    public TicketIssuerUnreachableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
