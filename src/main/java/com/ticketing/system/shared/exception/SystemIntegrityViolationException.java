package com.ticketing.system.shared.exception;
import com.ticketing.system.governance.application.service.SystemIntegrityVerifier;

// Thrown when a structural correctness constraint (requirements.md §1) is violated by the
// persisted state — detected by SystemIntegrityVerifier during UC-1 initialization.
public class SystemIntegrityViolationException extends DomainException {

    public SystemIntegrityViolationException(String reason) {
        super("System integrity violation: " + reason);
    }
}
