package com.ticketing.system.shared.exception;

// Thrown when an Owner attempts to edit an Event/VenueMap/Policy field that is
// frozen because tickets have already been sold. II.3.5.2 / II.4.5.2 immutability.
// UC-19, UC-20, UC-21.
public class EventNotEditableException extends DomainException {

    public EventNotEditableException(Object eventId, String field) {
        super("Event " + eventId + " field '" + field + "' is not editable after tickets are sold");
    }

    public EventNotEditableException(String message) {
        super(message);
    }
}
