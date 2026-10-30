package com.ticketing.system.shared.exception;

// Base for all domain-level exceptions. They reach the UI: a presenter that catches one shows its
// message to the user through ui/support/ServiceErrors.userMessage, and LoggingAspect logs the
// message at WARN. So the message must be fit for a user to read and carry no PII.
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
