package com.ticketing.system.shared.exception;

/**
 * The {@link EntityNotFoundException} for an {@code Event} lookup, thrown by the event repositories,
 * {@code CatalogService} and {@code EventManagementService}. It lets services catch selectively
 * (e.g. UC-19/20/21/22/31).
 */
public class EventNotFoundException extends EntityNotFoundException {

    /**
     * Creates the exception with an id-free message, for user-facing paths, so the id does not leak to
     * the UI (avoids exposing internal identifiers and enabling enumeration). A throw site that wants the
     * id in the server log writes it there itself.
     */
    public EventNotFoundException() {
        super("Event not found");
    }

    /**
     * Creates the exception for an event id that matched no event.
     *
     * <p>A hidden ({@code DRAFT} or {@code SCHEDULED}) event is refused with this same message, so a
     * caller cannot tell it from one that does not exist.
     *
     * @param eventId the id that was looked up
     */
    public EventNotFoundException(int eventId) {
        super("Event", eventId);
    }
}
