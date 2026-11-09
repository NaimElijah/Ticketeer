package com.ticketing.system.shared.exception;

/**
 * Thrown when a permission set is assigned to a company appointment that cannot hold one: only
 * manager appointments have permissions ({@code CompanyAppointment.updatePermissions}). UC-24.
 *
 * <p>A manager lacking a permission is not this exception: the permission gates
 * ({@code CompanyMembershipService.requirePermissionInCompany}) throw
 * {@link UnauthorizedActionException}. The message reaches the user.
 */
public class InvalidPermissionException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param requiredPermission the permission that is missing
     */
    public InvalidPermissionException(String requiredPermission) {
        super("Missing required permission: " + requiredPermission);
    }

    /**
     * Creates the exception for a user.
     *
     * @param requiredPermission the permission that is missing
     * @param userId the user who lacks it
     */
    public InvalidPermissionException(String requiredPermission, Object userId) {
        super("User " + userId + " lacks required permission: " + requiredPermission);
    }
}
