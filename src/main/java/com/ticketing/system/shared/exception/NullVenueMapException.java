package com.ticketing.system.shared.exception;

// Thrown when an event that exists has no venue map to show. Takes the event's id, since the map is
// looked up through its event: "Venue map not found: <eventId>".
public class NullVenueMapException extends EntityNotFoundException {

    public NullVenueMapException(int eventId) {
        super("Venue map", eventId);
    }
}
