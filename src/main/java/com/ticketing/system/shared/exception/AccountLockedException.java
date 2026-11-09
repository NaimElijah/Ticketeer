package com.ticketing.system.shared.exception;

/**
 * Thrown by member and admin sign-in when an account is temporarily locked after too many failed
 * attempts (upstream#290 / SLR.2 upstream#148).
 *
 * <p>Distinct from {@link AuthenticationFailedException}, so the view can show a "locked, try again
 * later" message rather than "invalid credentials". The message reaches the user.
 */
public class AccountLockedException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param message the refusal text the user reads
     */
    public AccountLockedException(String message) {
        super(message);
    }
}
