package com.ticketing.system.shared.exception;
import com.ticketing.system.identity.application.service.AuthenticationService;

/**
 * Thrown when an operation that mutates session state is invoked without an
 * active Guest session (D10a / login promotion). The caller must first call
 * {@code AuthenticationService.startGuestSession()} and pass the resulting
 * sessionId to the requested operation.
 */
public class GuestSessionRequiredException extends DomainException {

    public GuestSessionRequiredException(String reason) {
        super(reason);
    }
}
