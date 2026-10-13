package com.ticketing.system.shared.exception;

// Thrown when a payment-gateway idempotency key is reused with different parameters.
// UC-33 — defends against double-charge if a retry happens after a partial response.
public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key already used with different parameters: " + idempotencyKey);
    }
}
