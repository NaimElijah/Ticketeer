package com.ticketing.system.shared.exception;

// Thrown when a permission-check fails on a CompanyAppointment.
// Differs from UnauthorizedActionException: this is specifically about a Manager
// not having the right Permission value granted to them. UC-19/21/24.
public class InvalidPermissionException extends DomainException {

    public InvalidPermissionException(String requiredPermission) {
        super("Missing required permission: " + requiredPermission);
    }

    public InvalidPermissionException(String requiredPermission, Object userId) {
        super("User " + userId + " lacks required permission: " + requiredPermission);
    }
}
