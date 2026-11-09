package com.ticketing.system.shared.exception;

/**
 * Thrown when a concurrent writer got to a ticket or aggregate first: a seat no longer held by this
 * order, another cart holding the lines, an order changed mid-checkout, or a translated
 * {@code @Version} conflict ({@code OptimisticLockTranslationAspect}).
 *
 * <p>No service retries it: it reaches the user, who retries. {@code CheckoutService.runPhase3}
 * and the clean-up steps of {@code InventoryService} retry on Spring's {@code ConcurrencyFailureException}
 * instead, before any translation (ADR-015, ADR-017).
 */
public class ConcurrentReservationException extends DomainException {

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public ConcurrentReservationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the failure behind it.
     *
     * @param message the text the user reads
     * @param cause the underlying conflict, or {@code null}
     */
    public ConcurrentReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
