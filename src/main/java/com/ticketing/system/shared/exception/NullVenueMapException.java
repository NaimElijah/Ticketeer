package com.ticketing.system.shared.exception;

/**
 * Thrown when an event that exists has no venue map to show ({@code CatalogService}). It takes the
 * event's id, since the map is looked up through its event.
 */
public class NullVenueMapException extends EntityNotFoundException {

    /**
     * Creates the exception.
     *
     * @param eventId the event that has no venue map
     */
    public NullVenueMapException(int eventId) {
        super("Venue map", eventId);
    }
}
