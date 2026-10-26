package com.ticketing.system.shared.exception;

/**
 * Thrown when an email fails format validation. UC-11.
 * The message deliberately omits the address, so it never reaches the logs (SLR.2).
 */
public class InvalidEmailFormatException extends DomainException {

    public InvalidEmailFormatException() {
        super("Invalid email format");
    }
}
