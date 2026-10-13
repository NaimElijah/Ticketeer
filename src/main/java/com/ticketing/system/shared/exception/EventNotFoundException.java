package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for Event lookups.
// Lets services catch selectively (e.g. UC-19/20/21/22/31).
public class EventNotFoundException extends EntityNotFoundException {

    // Id-free message for user-facing paths — the id is written to the server log at the throw site,
    // not leaked to the UI (avoids exposing internal identifiers / enabling enumeration).
    public EventNotFoundException() {
        super("Event not found");
    }

    public EventNotFoundException(Object eventId) {
        super("Event not found: ", eventId);
    }
}
