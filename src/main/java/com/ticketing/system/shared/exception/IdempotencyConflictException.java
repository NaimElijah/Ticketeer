package com.ticketing.system.shared.exception;

/**
 * Thrown when an idempotency key is presented again for a different request. UC-33; it guards
 * against a double charge when a retry follows a partial response.
 *
 * <p>Two sites throw it: {@code CheckoutIdempotencyCache.find} when the key is cached for a different
 * buyer, so one buyer's purchase is never returned to another, and the stub payment gateway when a
 * key repeats with a different payment request. The message reaches the user.
 */
public class IdempotencyConflictException extends DomainException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key already used with different parameters: " + idempotencyKey);
    }
}
