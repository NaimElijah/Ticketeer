package com.ticketing.system.shared.exception;

// Thrown if SystemAdminService cannot create the default admin during startup.
// I.1.4 — UC-1. Almost always indicates an environment/configuration problem
// (missing seed credentials, DB unavailable for the bootstrap write).
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
