package com.ticketing.system.shared.exception;

// Thrown when an auto-refund itself fails (gateway down for the refund call).
// Distinct from PaymentGatewayException: this is the *refund* side, which has
// elevated SLR.5 fault-tolerance significance — a customer was charged but not refunded.
// UC-4. Listeners should escalate (logging, admin alert, retry queue).
public class RefundFailedException extends DomainException {

    public RefundFailedException(Object orderReceiptId, String reason) {
        super("Refund failed for order " + orderReceiptId + ": " + reason);
    }

    public RefundFailedException(Object orderReceiptId, String reason, Throwable cause) {
        super("Refund failed for order " + orderReceiptId + ": " + reason, cause);
    }
}
