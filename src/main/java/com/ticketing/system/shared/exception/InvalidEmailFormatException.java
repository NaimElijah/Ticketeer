package com.ticketing.system.shared.exception;

// Thrown when an email fails format validation. UC-11.
public class InvalidEmailFormatException extends DomainException {

    public InvalidEmailFormatException(String email) {
        super("Invalid email format: " + email);
    }
}
