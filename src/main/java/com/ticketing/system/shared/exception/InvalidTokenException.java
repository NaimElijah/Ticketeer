package com.ticketing.system.shared.exception;

/**
 * Thrown when a token cannot be accepted: it is malformed, its signature is wrong, its session row is
 * revoked or names another kind of session, or it is otherwise unreadable (UC-12). It differs from
 * {@link AuthenticationFailedException}, which is the login flow.
 *
 * <p>{@link SessionExpiredException} is the expired case and a subtype, so catching this type handles
 * every dead token. A caller that must tell an expiry apart catches the subtype first.
 */
public class InvalidTokenException extends DomainException {

    public InvalidTokenException() {
        super("Invalid authentication token");
    }

    /** @param reason what made the token unacceptable; it is appended to the message */
    public InvalidTokenException(String reason) {
        super("Invalid authentication token: " + reason);
    }

    /**
     * For a subtype with its own wording, which the public constructors would prefix with the bad-token
     * text.
     *
     * @param message the whole message, as the user may read it
     * @param cause   the failure behind it, or {@code null}
     */
    protected InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
