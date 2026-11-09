package com.ticketing.system.shared.exception;

/**
 * Thrown when an idempotency key is presented again for a different request. UC-33.
 *
 * <p>The only production thrower is {@code CheckoutIdempotencyCache.find}, when the key is cached for
 * a different buyer, so one buyer's purchase is never returned to another. The stub payment gateway
 * (test profile) throws it when a key repeats with a different payment request. Checkout reports a
 * duplicate submission without the message.
 */
public class IdempotencyConflictException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param idempotencyKey the key that was presented again
     */
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key already used with different parameters: " + idempotencyKey);
    }
}
