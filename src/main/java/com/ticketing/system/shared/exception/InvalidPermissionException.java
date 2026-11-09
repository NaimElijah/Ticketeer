package com.ticketing.system.shared.exception;

/**
 * Thrown when a permission set is assigned to a company appointment that cannot hold one: only
 * manager appointments have permissions ({@code CompanyAppointment.updatePermissions}). UC-24.
 *
 * <p>No production code throws it today: {@code CompanyMembershipService.modifyManagerPermissions}
 * refuses a non-manager appointment with a {@link BusinessRuleViolationException} first. A manager
 * lacking a permission is not this exception either: the permission gates
 * ({@code CompanyMembershipService.requirePermissionInCompany}) throw
 * {@link UnauthorizedActionException}.
 */
public class InvalidPermissionException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param requiredPermission what is missing or refused; the caller passes a sentence, which the
     *        message follows with "Missing required permission: "
     */
    public InvalidPermissionException(String requiredPermission) {
        super("Missing required permission: " + requiredPermission);
    }

    /**
     * Creates the exception for a user.
     *
     * @param requiredPermission what is missing or refused, as a sentence that the message appends
     * @param userId the user who lacks it
     */
    public InvalidPermissionException(String requiredPermission, Object userId) {
        super("User " + userId + " lacks required permission: " + requiredPermission);
    }
}
