package com.ticketing.system.shared.exception;

/**
 * The platform could not establish its default System Admin (UC-1, I.1.4), so it has no admin at all.
 *
 * <p>Thrown by {@code SystemAdminService.createDefaultAdminIfMissing} when no admin exists and saving the
 * default one fails, or when the save reports success but no admin can be found afterwards. It almost
 * always means an environment problem: the database unavailable for the bootstrap write, or invalid
 * {@code platform.admin.*} settings. Initialization does not treat it as transient: at boot it stops the
 * application, and the market self-heal logs it at ERROR on every tick (ADR-003).
 *
 * <p>Carries the reason in its message, prefixed "Could not establish default System Admin: ", and, when
 * a save failed, that failure as its cause; a failing re-check after the save is attached as suppressed.
 * The message is logged, so it never contains the admin's credentials.
 */
public class MissingDefaultAdminException extends DomainException {

    public MissingDefaultAdminException(String reason) {
        super("Could not establish default System Admin: " + reason);
    }

    /**
     * @param reason what went wrong, without credentials: the message is logged
     * @param cause the failure that kept the default admin from being created
     */
    public MissingDefaultAdminException(String reason, Throwable cause) {
        super("Could not establish default System Admin: " + reason, cause);
    }
}
