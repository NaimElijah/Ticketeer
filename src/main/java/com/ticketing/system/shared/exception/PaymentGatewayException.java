package com.ticketing.system.shared.exception;

// Thrown when an external payment-gateway call fails (decline, timeout, unavailable) or its reply
// fails CheckoutService's validation. UC-33.
// A charge is never retried, whatever the cause: the failure does not say whether WSEP took the money,
// so a second `pay` could double-charge (WsepHttpClient.postWithRetry is for the idempotent handshake
// only). CheckoutService aborts the checkout instead: it returns the reserved tickets to stock and
// refunds only when a charge result had come back (UC-4).
public class PaymentGatewayException extends DomainException {

    public PaymentGatewayException(String reason) {
        super("Payment gateway failure: " + reason);
    }

    public PaymentGatewayException(String reason, Throwable cause) {
        super("Payment gateway failure: " + reason, cause);
    }
}
