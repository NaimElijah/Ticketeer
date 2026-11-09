package com.ticketing.system.shared.exception;

/**
 * Thrown when an external payment-gateway call fails (decline, timeout, unavailable) or its reply
 * fails {@code CheckoutService}'s validation. UC-33.
 *
 * <p>A charge is never retried, whatever the cause: the failure does not say whether WSEP took the
 * money, so a second {@code pay} could double-charge ({@code WsepHttpClient.postWithRetry} is for the
 * idempotent handshake only). {@code CheckoutService} aborts the checkout instead: it returns the
 * reserved tickets to stock and refunds only when a charge result had come back (UC-4).
 */
public class PaymentGatewayException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason what failed; the message prefixes it with "Payment gateway failure: "
     */
    public PaymentGatewayException(String reason) {
        super("Payment gateway failure: " + reason);
    }

    /**
     * Creates the exception with the failure behind it.
     *
     * @param reason what failed; the message prefixes it with "Payment gateway failure: "
     * @param cause the underlying failure, or {@code null}
     */
    public PaymentGatewayException(String reason, Throwable cause) {
        super("Payment gateway failure: " + reason, cause);
    }
}
