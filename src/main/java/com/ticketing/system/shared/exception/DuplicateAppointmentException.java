package com.ticketing.system.shared.exception;

/**
 * Reserved for appointing someone who already holds or has pending an appointment in the company.
 * UC-24, and the "one appointer per user" invariant from II.4.8.3.
 *
 * <p>No production code throws it today: {@code CompanyMembershipService} refuses such an
 * appointment with a {@link BusinessRuleViolationException}.
 */
public class DuplicateAppointmentException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param userId the user who already has an appointment
     * @param companyId the company of that appointment
     */
    public DuplicateAppointmentException(Object userId, Object companyId) {
        super("User " + userId + " already has an appointment at company " + companyId);
    }
}
