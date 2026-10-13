package com.ticketing.system.shared.exception;

// Thrown by UC-1 step 4 / I.1.1 when the platform post-conditions do not hold at the
// integrity gate (e.g. no admin present after setup, or a required external service became
// unreachable between the initial check and the gate).
public class InitializationIntegrityException extends DomainException {

    public InitializationIntegrityException(String reason) {
        super("Platform initialization integrity check failed: " + reason);
    }
}
