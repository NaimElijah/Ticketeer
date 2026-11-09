package com.ticketing.system.shared.exception;

/**
 * Thrown when an operation references an appointment that does not exist: accepting, rejecting or
 * editing one ({@code CompanyMembershipService}), or revoking the appointment of a user who is neither
 * manager nor owner ({@code ProductionCompany}). UC-23 (responding to a non-existent invitation),
 * UC-24 (editing or revoking a non-existent appointment). The message reaches the user.
 */
public class AppointmentNotFoundException extends DomainException {

    /**
     * Creates the exception for a user with no appointment at a company.
     *
     * @param companyId the company searched
     * @param userId the user searched for
     */
    public AppointmentNotFoundException(Object companyId, Object userId) {
        super("No appointment found for user " + userId + " at company " + companyId);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public AppointmentNotFoundException(String message) {
        super(message);
    }
}
