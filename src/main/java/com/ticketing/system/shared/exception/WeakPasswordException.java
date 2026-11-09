package com.ticketing.system.shared.exception;

/**
 * Thrown by {@code AuthenticationService.register} when the password is too short, longer than the
 * limit in UTF-8 bytes, or lacks a letter or a digit. UC-11. SLR.2 (data security and privacy).
 *
 * <p>The message states the rule that failed and never contains the password. It reaches the user.
 */
public class WeakPasswordException extends DomainException {

    public WeakPasswordException(String reason) {
        super("Password does not meet security requirements: " + reason);
    }
}
