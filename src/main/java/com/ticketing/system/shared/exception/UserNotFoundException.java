package com.ticketing.system.shared.exception;
import com.ticketing.system.identity.domain.User;

// Specific subclass of EntityNotFoundException for User lookups.
// UC-12, UC-13, UC-16, UC-23/24, UC-31, etc.
public class UserNotFoundException extends EntityNotFoundException {

    // Id-free message for user-facing paths — the id is written to the server log at the throw site,
    // not leaked to the UI (avoids exposing internal identifiers / enabling enumeration).
    public UserNotFoundException() {
        super("User not found");
    }

    public UserNotFoundException(Object userId) {
        super("User", userId);
    }
}
