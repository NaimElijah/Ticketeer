package com.ticketing.system.shared.exception;

/**
 * Thrown by {@code AuthenticationService.register} when the password is too short, longer than the
 * limit in UTF-8 bytes, or lacks a letter or a digit. UC-11. SLR.2 (data security and privacy).
 *
 * <p>The message reaches the user.
 */
public class WeakPasswordException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason the rule that failed; the message prefixes it
     */
    public WeakPasswordException(String reason) {
        super("Password does not meet security requirements: " + reason);
    }
}
