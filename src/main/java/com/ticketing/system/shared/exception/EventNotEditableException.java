package com.ticketing.system.shared.exception;

/**
 * Reserved for an edit of an event, venue map or policy field that is frozen because tickets have
 * been sold. II.3.5.2 / II.4.5.2 immutability. UC-19, UC-20, UC-21.
 *
 * <p>No production code throws it today; {@code Event} refuses a venue-map change after sales with
 * an {@link IllegalStateException}.
 */
public class EventNotEditableException extends DomainException {

    public EventNotEditableException(Object eventId, String field) {
        super("Event " + eventId + " field '" + field + "' is not editable after tickets are sold");
    }

    public EventNotEditableException(String message) {
        super(message);
    }
}
