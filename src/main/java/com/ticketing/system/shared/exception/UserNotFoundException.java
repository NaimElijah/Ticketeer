package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for User lookups.
// UC-12, UC-13, UC-16, UC-23/24, UC-31, etc.
public class UserNotFoundException extends EntityNotFoundException {

    // Id-free message for user-facing paths, so the id does not leak to the UI (avoids exposing
    // internal identifiers / enabling enumeration). A throw site that wants the id in the server log
    // writes it there itself.
    public UserNotFoundException() {
        super("User not found");
    }

    // "User not found: <id>".
    public UserNotFoundException(int userId) {
        super("User", userId);
    }
}
