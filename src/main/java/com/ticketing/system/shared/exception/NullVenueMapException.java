package com.ticketing.system.shared.exception;

public class NullVenueMapException extends EntityNotFoundException {
    public NullVenueMapException(Object eventId) {
        super("Venue map not found for event: ", eventId);
    }

}
