package com.ticketing.system.shared.exception;

// Catch-all for invariant violations that don't have a more specific exception.
// Prefer a specific subclass when one fits — this is for the long tail.
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
