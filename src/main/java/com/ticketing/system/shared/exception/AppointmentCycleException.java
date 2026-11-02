package com.ticketing.system.shared.exception;

// Thrown when an appointment would create a cycle in the company's appointment tree.
// II.4.8.3 — UC-23 (Appoint Co-Owner). Not thrown today: nothing checks for a cycle, and a revoked
// owner can be re-appointed by their own appointee, which forms one (NaimElijah/Ticketeer#126). The
// acceptance test for cycles is disabled (NaimElijah/Ticketeer#52). See II.4.8 in requirements.md.
public class AppointmentCycleException extends DomainException {

    public AppointmentCycleException(Object appointerId, Object appointeeId) {
        super("Appointment from " + appointerId + " to " + appointeeId
              + " would create a cycle in the appointment tree");
    }
}
