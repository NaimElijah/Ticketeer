package com.ticketing.system.shared.exception;

/**
 * Reserved for an operation on an event that is already {@code CANCELED}, as a clearer error than
 * {@link InvalidStateTransitionException}. UC-19, UC-9, UC-10.
 *
 * <p>No production code throws it today. A reservation on such an event is refused with an
 * {@link IllegalStateException}. Cancelling it again is not an error: {@code Event.transitionToCanceled}
 * ignores a repeat, and {@code EventManagementService.cancelEventAndRefund} uses one to resume refunds
 * that are still pending.
 */
public class EventAlreadyCanceledException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param eventId the event that is already canceled
     */
    public EventAlreadyCanceledException(Object eventId) {
        super("Event " + eventId + " is already canceled");
    }
}
