package com.ticketing.system.shared.exception;

/**
 * The ticket issuer was unreachable or gave no usable answer: a connection refused or timed out, an I/O
 * error, a non-2xx status, or a body larger than any reply, so issuance never got a verdict. Each is an
 * outage that needs an operator, unlike a refusal by the issuer, which stays a plain
 * {@link TicketIssuanceFailedException}. It extends that type, so every existing catch and the
 * presenter's reason-free outcome still apply. Mirrors {@link PaymentGatewayUnreachableException}.
 */
public class TicketIssuerUnreachableException extends TicketIssuanceFailedException {

    /**
     * @param reason what failed
     * @param cause  the transport failure, or the answer that could not be used
     */
    public TicketIssuerUnreachableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
