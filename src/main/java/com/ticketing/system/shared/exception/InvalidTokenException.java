package com.ticketing.system.shared.exception;

// Thrown when a JWT token is malformed, has an invalid signature, or otherwise can't be parsed.
// Distinct from SessionExpiredException (token was valid, just timed out) and
// AuthenticationFailedException (login flow). UC-12 token validation.
public class InvalidTokenException extends DomainException {

    public InvalidTokenException() {
        super("Invalid authentication token");
    }

    public InvalidTokenException(String reason) {
        super("Invalid authentication token: " + reason);
    }
}
