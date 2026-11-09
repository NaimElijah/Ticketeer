package com.ticketing.system.shared.exception;

/**
 * Reserved for an edit of an event, venue map or policy field that is frozen because tickets have
 * been sold. II.3.5.2 / II.4.5.2 immutability. UC-19, UC-20, UC-21.
 *
 * <p>No production code throws it today; {@code Event} refuses a venue-map change after sales with
 * an {@link IllegalStateException}.
 */
public class EventNotEditableException extends DomainException {

    /**
     * Creates the exception for a frozen field.
     *
     * @param eventId the event being edited
     * @param field the name of the field that is frozen
     */
    public EventNotEditableException(Object eventId, String field) {
        super("Event " + eventId + " field '" + field + "' is not editable after tickets are sold");
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public EventNotEditableException(String message) {
        super(message);
    }
}
