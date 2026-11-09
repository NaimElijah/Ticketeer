package com.ticketing.system.shared.exception;

/**
 * Reserved for an operation on an event that is already {@code CANCELED}, as a clearer error than
 * {@link InvalidStateTransitionException}. UC-19, UC-9, UC-10.
 *
 * <p>No production code throws it today. A reservation on such an event is refused with an
 * {@link IllegalStateException}, and cancelling it again does nothing.
 */
public class EventAlreadyCanceledException extends DomainException {

    public EventAlreadyCanceledException(Object eventId) {
        super("Event " + eventId + " is already canceled");
    }
}
