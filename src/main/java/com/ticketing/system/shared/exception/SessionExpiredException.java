package com.ticketing.system.shared.exception;

/**
 * Thrown when a JWT is well-formed but past its expiry, or its session row has expired: an
 * already-issued token failing validation on a later request (UC-12). It differs from
 * {@link AuthenticationFailedException}, which is the login flow.
 *
 * <p>An expired token is an invalid token, so this extends {@link InvalidTokenException}. Every
 * caller that refuses a dead token by catching the parent therefore handles an expiry the same way,
 * and a presenter sends the visitor to sign in instead of showing a failure (#122).
 */
public class SessionExpiredException extends InvalidTokenException {

    private static final String MESSAGE = "Session expired; please re-authenticate";

    public SessionExpiredException() {
        super();
    }

    // The parent's constructors word their message for a bad token; an expiry keeps its own.
    @Override
    public String getMessage() {
        return MESSAGE;
    }
}
