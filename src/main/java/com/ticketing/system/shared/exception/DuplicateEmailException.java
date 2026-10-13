package com.ticketing.system.shared.exception;

/** Thrown when registration attempts to use an email already on record. UC-11. */
public class DuplicateEmailException extends DomainException {

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
    }
}
