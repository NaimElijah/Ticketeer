package com.ticketing.system.shared.exception;

/**
 * Thrown by {@code SystemAdminService} during UC-1 (I.1.2 / I.1.3) and UC-32 (I.2.2) when no external
 * payment or ticket-issuance service is reachable, so the platform cannot be brought up or the market
 * cannot be opened. It reports the WSEP {@code handshake} action not returning OK. The message
 * reaches the user.
 */
public class ExternalServiceUnavailableException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason which service is unavailable
     */
    public ExternalServiceUnavailableException(String reason) {
        super("Required external service unavailable: " + reason);
    }
}
