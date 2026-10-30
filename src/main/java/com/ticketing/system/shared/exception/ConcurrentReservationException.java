package com.ticketing.system.shared.exception;

// Thrown when a concurrent writer got to a Ticket / aggregate first: a seat no longer held by this
// order, another cart holding the lines, an order changed mid-checkout, or a translated @Version
// conflict (OptimisticLockTranslationAspect). No service retries it; it reaches the user, who retries.
// CheckoutService.runPhase3 retries on Spring's ConcurrencyFailureException instead, before any
// translation (ADR-015).
public class ConcurrentReservationException extends DomainException {

    public ConcurrentReservationException(String message) {
        super(message);
    }

    public ConcurrentReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
