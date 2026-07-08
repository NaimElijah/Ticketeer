package com.ticketing.system.shared.exception;

// Thrown when an appointment would create a cycle in the company's appointment tree.
// II.4.8.3 — UC-23 (Appoint Co-Owner). Enforced by ProductionCompany.canAppoint(...).
public class AppointmentCycleException extends DomainException {

    public AppointmentCycleException(Object appointerId, Object appointeeId) {
        super("Appointment from " + appointerId + " to " + appointeeId
              + " would create a cycle in the appointment tree");
    }
}
