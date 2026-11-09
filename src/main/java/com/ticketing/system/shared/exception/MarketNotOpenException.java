package com.ticketing.system.shared.exception;

/**
 * Thrown when a reservation or checkout is attempted while the market is not {@code OPEN}
 * ({@code ReservationService}, {@code CheckoutService}), and when an admin opens the market of a
 * platform that is still uninitialized ({@code SystemAdminService}). UC-32: the market must be open
 * before transactions are allowed.
 *
 * <p>The message reaches the user.
 */
public class MarketNotOpenException extends DomainException {

    /** Creates the exception with the standard text. */
    public MarketNotOpenException() {
        super("Market is not currently open for transactions");
    }

    /**
     * Creates the exception with a reason.
     *
     * @param reason why the market is not open
     */
    public MarketNotOpenException(String reason) {
        super("Market is not open: " + reason);
    }
}
