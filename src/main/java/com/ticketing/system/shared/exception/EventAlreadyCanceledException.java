package com.ticketing.system.shared.exception;

// Thrown when an operation is attempted on an already-CANCELED event.
// Distinct from InvalidStateTransitionException — gives a clearer error.
// UC-19, UC-9, UC-10.
public class EventAlreadyCanceledException extends DomainException {

    public EventAlreadyCanceledException(Object eventId) {
        super("Event " + eventId + " is already canceled");
    }
}
