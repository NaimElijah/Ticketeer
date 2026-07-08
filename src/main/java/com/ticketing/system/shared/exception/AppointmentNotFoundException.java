package com.ticketing.system.shared.exception;

// Thrown when an operation references an appointment that doesn't exist.
// UC-23 (responding to a non-existent invitation), UC-24 (editing/revoking a non-existent appointment).
public class AppointmentNotFoundException extends DomainException {

    public AppointmentNotFoundException(Object companyId, Object userId) {
        super("No appointment found for user " + userId + " at company " + companyId);
    }

    public AppointmentNotFoundException(String message) {
        super(message);
    }
}
