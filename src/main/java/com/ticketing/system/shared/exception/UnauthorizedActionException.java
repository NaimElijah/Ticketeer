package com.ticketing.system.shared.exception;

/**
 * Thrown when an actor attempts an action they are not permitted to perform: an owner-only or
 * permission-gated company action, an event of another company, a receipt that is not the caller's,
 * an admin-only operation with a token that is not a live admin's, or a company inbox the caller
 * does not act for. Authorization is a domain concern as well as an application-service one, so both
 * {@code ProductionCompany} and {@code Event} throw it, besides the services (UC-19/21/22/23/24/25,
 * UC-16, UC-31/32).
 *
 * <p>The message states the refused action and reaches the user.
 */
public class UnauthorizedActionException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param action the action that was refused
     */
    public UnauthorizedActionException(String action) {
        super("Not authorized to perform action: " + action);
    }

    /**
     * Creates the exception for a known actor.
     *
     * @param action the action that was refused
     * @param actorId the actor who attempted it
     */
    public UnauthorizedActionException(String action, Object actorId) {
        super("Actor " + actorId + " not authorized to perform action: " + action);
    }
}
