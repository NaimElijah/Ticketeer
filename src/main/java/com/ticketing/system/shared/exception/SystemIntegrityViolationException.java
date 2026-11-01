package com.ticketing.system.shared.exception;

/**
 * The persisted state breaks a structural correctness constraint (requirements.md, Business rules §1),
 * or a loaded aggregate breaks its own invariant.
 *
 * <p>Thrown by {@code SystemIntegrityVerifier}, which runs during initialization (UC-1) and again before
 * the market opens (UC-32). Retrying does not repair the data: at boot it stops the application, while
 * the platform is running it keeps the market from opening, and the market self-heal logs it at ERROR on
 * every tick until someone fixes the rows (ADR-003).
 *
 * <p>Carries the violated constraint and the offending records in its message, prefixed
 * "System integrity violation: ", and, when another failure revealed the violation (an invariant check
 * or a missing user), that failure as its cause. The message is logged, so it names records by id or
 * order key, never by a name, a username or a session id.
 */
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
