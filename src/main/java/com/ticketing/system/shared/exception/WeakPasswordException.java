package com.ticketing.system.shared.exception;

// Thrown when a password fails complexity rules at registration / change.
// UC-11. SLR.2 (data security & privacy).
public class WeakPasswordException extends DomainException {

    public WeakPasswordException(String reason) {
        super("Password does not meet security requirements: " + reason);
    }
}
