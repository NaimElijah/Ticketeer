package com.ticketing.system.shared.exception;

/**
 * Thrown when a JWT is well-formed but past its expiry, or its session row has expired: an
 * already-issued token failing validation on a later request (UC-12). Also thrown on a guest path for
 * a session id that is not a live guest row ({@code ReservationService.requireLiveGuestSession},
 * {@code CheckoutService.validateGuestSessionLive}; ADR-012, decision 4), so a guest path's caller
 * must not read it as "sign in". It differs from {@link AuthenticationFailedException}, which is the
 * login flow.
 *
 * <p>An expired token is an invalid token, so this extends {@link InvalidTokenException}. A presenter
 * that maps {@code InvalidTokenException} to its not-authenticated outcome now does so for an expiry
 * too (#122).
 */
public class SessionExpiredException extends InvalidTokenException {

    private static final String MESSAGE = "Session expired; please re-authenticate";

    public SessionExpiredException() {
        super(MESSAGE, null);
    }
}
