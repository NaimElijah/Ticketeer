package com.ticketing.system.shared.exception;

/**
 * Thrown when no System Admin exists at the integrity gate of platform initialization (UC-1 step 4,
 * I.1.1) or of opening the market (UC-32, I.2.1), both in {@code SystemAdminService}.
 *
 * <p>An unreachable external service at those gates is an {@link ExternalServiceUnavailableException}
 * instead. At boot a failure here stops the application (ADR-003). The message names the reason and
 * reaches the user.
 */
public class InitializationIntegrityException extends DomainException {

    public InitializationIntegrityException(String reason) {
        super("Platform initialization integrity check failed: " + reason);
    }
}
