package com.ticketing.system.shared.exception;
import com.ticketing.system.identity.domain.User;

// Thrown when attempting to appoint someone who is already a Manager / Owner / pending.
// UC-24 — and the "one appointer per user" invariant from II.4.8.3.
public class DuplicateAppointmentException extends DomainException {

    public DuplicateAppointmentException(Object userId, Object companyId) {
        super("User " + userId + " already has an appointment at company " + companyId);
    }
}
