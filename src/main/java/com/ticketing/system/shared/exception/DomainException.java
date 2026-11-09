package com.ticketing.system.shared.exception;

/**
 * Base of the domain-level exceptions, which carry a message written for a user to read.
 *
 * <p>They reach the UI: a presenter that catches one shows its message through
 * {@code ui/support/ServiceErrors.userMessage}, and {@code LoggingAspect} logs the message at WARN. So
 * a message must be fit for a user to read and carry no personal data.
 */
public abstract class DomainException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the text a user may read; free of personal data
     */
    protected DomainException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the failure behind it.
     *
     * @param message the text a user may read; free of personal data
     * @param cause the underlying failure, or {@code null}
     */
    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
