package com.ticketing.system.shared.exception;

// Thrown when a structural correctness constraint (docs/requirements/requirements.md §1) is violated by the
// persisted state — detected by SystemIntegrityVerifier during UC-1 initialization.
public class SystemIntegrityViolationException extends DomainException {

    public SystemIntegrityViolationException(String reason) {
        super("System integrity violation: " + reason);
    }

    /**
     * @param reason the violated constraint and the record that breaks it, without personal data: the
     *        message is logged
     * @param cause the failure that revealed the violation
     */
    public SystemIntegrityViolationException(String reason, Throwable cause) {
        super("System integrity violation: " + reason, cause);
    }
}
