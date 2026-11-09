package com.ticketing.system.shared.exception;

/**
 * Thrown when no System Admin exists at the integrity gate of platform initialization (UC-1 step 4,
 * I.1.1) or of opening the market (UC-32, I.2.1), both in {@code SystemAdminService}.
 *
 * <p>An unreachable external service at those gates is an {@link ExternalServiceUnavailableException}
 * instead. At boot a failure at the initialization gate stops the application (ADR-003); at the market-open
 * gate the market self-heal logs it and retries.
 */
public class InitializationIntegrityException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason the post-condition that does not hold
     */
    public InitializationIntegrityException(String reason) {
        super("Platform initialization integrity check failed: " + reason);
    }
}
