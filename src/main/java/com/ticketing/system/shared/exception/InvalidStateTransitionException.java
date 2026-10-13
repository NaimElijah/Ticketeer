package com.ticketing.system.shared.exception;

// Thrown when an aggregate's state machine rejects a requested transition.
// Examples: Ticket REFUNDED → RESERVED (illegal); Event COMPLETED → ON_SALE (illegal).
// Generic — used by Ticket, Event, ActiveOrder, CompanyAppointment, Notification, etc.
public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super(entityType + " cannot transition from " + fromState + " to " + toState);
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
