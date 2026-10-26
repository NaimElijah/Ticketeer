package com.ticketing.system.shared.exception;

/**
 * Thrown when registration attempts to use an email already on record. UC-11.
 * The message deliberately omits the address, so it never reaches the logs (SLR.2).
 */
public class DuplicateEmailException extends DomainException {

    public DuplicateEmailException() {
        super("Email already registered");
    }
}
